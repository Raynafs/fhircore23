/*
 * Copyright 2021-2024 Ona Systems, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.quest.ui.main

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material.ExperimentalMaterialApi
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.sync.CurrentSyncJobStatus
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.AndroidEntryPoint
import io.sentry.android.navigation.SentryNavigationListener
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.configuration.app.ConfigService
import org.smartregister.fhircore.engine.configuration.app.SyncStrategy
import org.smartregister.fhircore.engine.configuration.workflow.ActionTrigger
import org.smartregister.fhircore.engine.datastore.ProtoDataStore
import org.smartregister.fhircore.engine.datastore.syncLocationIdsProtoStore
import org.smartregister.fhircore.engine.rulesengine.services.LocationCoordinate
import org.smartregister.fhircore.engine.sync.OnSyncListener
import org.smartregister.fhircore.engine.sync.SyncBroadcaster
import org.smartregister.fhircore.engine.sync.SyncListenerManager
import org.smartregister.fhircore.engine.ui.base.BaseMultiLanguageActivity
import org.smartregister.fhircore.engine.util.DefaultDispatcherProvider
import org.smartregister.fhircore.engine.util.extension.isDeviceOnline
import org.smartregister.fhircore.engine.util.extension.parcelable
import org.smartregister.fhircore.engine.util.extension.serializable
import org.smartregister.fhircore.engine.util.extension.showToast
import org.smartregister.fhircore.engine.util.location.LocationUtils
import org.smartregister.fhircore.engine.util.location.PermissionUtils
import org.smartregister.fhircore.geowidget.model.GeoWidgetEvent
import org.smartregister.fhircore.geowidget.screens.GeoWidgetViewModel
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.event.AppEvent
import org.smartregister.fhircore.quest.event.EventBus
import org.smartregister.fhircore.quest.navigation.NavigationArg
import org.smartregister.fhircore.quest.ui.questionnaire.QuestionnaireActivity
import org.smartregister.fhircore.quest.ui.shared.QuestionnaireHandler
import org.smartregister.fhircore.quest.ui.shared.models.QuestionnaireSubmission
import timber.log.Timber

@AndroidEntryPoint
@ExperimentalMaterialApi
open class AppMainActivity : BaseMultiLanguageActivity(), QuestionnaireHandler, OnSyncListener {

  @Inject lateinit var dispatcherProvider: DefaultDispatcherProvider

  @Inject lateinit var configService: ConfigService

  @Inject lateinit var syncListenerManager: SyncListenerManager

  @Inject lateinit var syncBroadcaster: SyncBroadcaster

  @Inject lateinit var fhirEngine: FhirEngine

  @Inject lateinit var protoDataStore: ProtoDataStore

  @Inject lateinit var eventBus: EventBus
  lateinit var navHostFragment: NavHostFragment
  val appMainViewModel by viewModels<AppMainViewModel>()
  private val geoWidgetViewModel by viewModels<GeoWidgetViewModel>()
  private val sentryNavListener =
    SentryNavigationListener(enableNavigationBreadcrumbs = true, enableNavigationTracing = true)
  private lateinit var locationPermissionLauncher: ActivityResultLauncher<Array<String>>
  private lateinit var activityResultLauncher: ActivityResultLauncher<Intent>
  private lateinit var fusedLocationClient: FusedLocationProviderClient

  override val startForResult =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
      if (activityResult.resultCode == Activity.RESULT_OK) {
        lifecycleScope.launch { onSubmitQuestionnaire(activityResult) }
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setupLocationServices()
    setContentView(FragmentContainerView(this).apply { id = R.id.nav_host })
    val topMenuConfig = appMainViewModel.navigationConfiguration.clientRegisters.first()
    val topMenuConfigId =
      topMenuConfig.actions?.find { it.trigger == ActionTrigger.ON_CLICK }?.id ?: topMenuConfig.id
    navHostFragment =
      NavHostFragment.create(
        R.navigation.application_nav_graph,
        bundleOf(
          NavigationArg.SCREEN_TITLE to topMenuConfig.display,
          NavigationArg.REGISTER_ID to topMenuConfigId,
        ),
      )

    supportFragmentManager
      .beginTransaction()
      .replace(R.id.nav_host, navHostFragment)
      .setPrimaryNavigationFragment(navHostFragment)
      .commit()

    geoWidgetViewModel.geoWidgetEventLiveData.observe(this) { geoWidgetEvent ->
      when (geoWidgetEvent) {
        is GeoWidgetEvent.OpenProfile ->
          appMainViewModel.launchProfileFromGeoWidget(
            navHostFragment.navController,
            geoWidgetEvent.geoWidgetConfiguration.id,
            geoWidgetEvent.data,
          )
        is GeoWidgetEvent.RegisterClient ->
          appMainViewModel.launchFamilyRegistrationWithLocationId(
            context = this,
            locationId = geoWidgetEvent.data,
            questionnaireConfig = geoWidgetEvent.questionnaire,
          )
      }
    }

    // Register sync listener then run sync in that order
    syncListenerManager.registerSyncListener(this, lifecycle)

    // Setup the drawer and schedule jobs
    appMainViewModel.run {
      lifecycleScope.launch {
        retrieveAppMainUiState()
        if (isDeviceOnline()) {
          // Do not schedule sync until location selected when strategy is RelatedEntityLocation
          // Use applicationConfiguration.usePractitionerAssignedLocationOnSync to identify
          // if we need to trigger sync based on assigned locations or not
          if (applicationConfiguration.syncStrategy.contains(SyncStrategy.RelatedEntityLocation)) {
            if (
              applicationConfiguration.usePractitionerAssignedLocationOnSync ||
                syncLocationIdsProtoStore.data.firstOrNull()?.isNotEmpty() == true
            ) {
              triggerSync()
            }
          } else {
            triggerSync()
          }
        } else {
          showToast(
            getString(org.smartregister.fhircore.engine.R.string.sync_failed),
            Toast.LENGTH_LONG,
          )
        }
      }
      schedulePeriodicJobs()
    }
  }

  override fun onResume() {
    super.onResume()
    navHostFragment.navController.addOnDestinationChangedListener(sentryNavListener)
    syncListenerManager.registerSyncListener(this, lifecycle)
  }

  override fun onPause() {
    super.onPause()
    navHostFragment.navController.removeOnDestinationChangedListener(sentryNavListener)
  }

  override suspend fun onSubmitQuestionnaire(activityResult: ActivityResult) {
    if (activityResult.resultCode == RESULT_OK) {
      val questionnaireResponse: QuestionnaireResponse? =
        activityResult.data?.serializable(QuestionnaireActivity.QUESTIONNAIRE_RESPONSE)
          as QuestionnaireResponse?
      val extractedResourceIds =
        activityResult.data?.serializable(
          QuestionnaireActivity.QUESTIONNAIRE_SUBMISSION_EXTRACTED_RESOURCE_IDS,
        ) as List<IdType>? ?: emptyList()
      val questionnaireConfig =
        activityResult.data?.parcelable(QuestionnaireActivity.QUESTIONNAIRE_CONFIG)
          as QuestionnaireConfig?

      if (questionnaireConfig != null && questionnaireResponse != null) {
        eventBus.triggerEvent(
          AppEvent.OnSubmitQuestionnaire(
            QuestionnaireSubmission(
              questionnaireConfig = questionnaireConfig,
              questionnaireResponse = questionnaireResponse,
              extractedResourceIds = extractedResourceIds,
            ),
          ),
        )
      } else Timber.e("QuestionnaireConfig & QuestionnaireResponse are both null")
    }
  }

  private fun setupLocationServices() {
    fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

    if (!LocationUtils.isLocationEnabled(this)) {
      openLocationServicesSettings()
    }

    if (!hasLocationPermissions()) {
      launchLocationPermissionsDialog()
    }

    if (LocationUtils.isLocationEnabled(this) && hasLocationPermissions()) {
      fetchLocation()
    }
  }

  fun hasLocationPermissions(): Boolean {
    return PermissionUtils.checkPermissions(
      this,
      listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
      ),
    )
  }

  private fun openLocationServicesSettings() {
    activityResultLauncher =
      PermissionUtils.getStartActivityForResultLauncher(this) { resultCode, _ ->
        if (resultCode == RESULT_OK || hasLocationPermissions()) {
          Timber.d("Location or permissions successfully enabled")
        }
      }

    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    showLocationSettingsDialog(intent)
  }

  private fun showLocationSettingsDialog(intent: Intent) {
    AlertDialog.Builder(this)
      .setMessage(getString(R.string.location_services_disabled))
      .setCancelable(true)
      .setPositiveButton(getString(R.string.yes)) { _, _ -> activityResultLauncher.launch(intent) }
      .setNegativeButton(getString(R.string.no)) { dialog, _ -> dialog.cancel() }
      .show()
  }

  fun launchLocationPermissionsDialog() {
    locationPermissionLauncher =
      PermissionUtils.getLocationPermissionLauncher(
        this,
        onFineLocationPermissionGranted = { fetchLocation() },
        onCoarseLocationPermissionGranted = { fetchLocation() },
        onLocationPermissionDenied = {
          Toast.makeText(
              this,
              getString(R.string.location_permissions_denied),
              Toast.LENGTH_SHORT,
            )
            .show()
        },
      )

    locationPermissionLauncher.launch(
      arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
      ),
    )
  }

  fun fetchLocation() {
    val context = this
    lifecycleScope.launch {
      val retrievedLocation =
        if (PermissionUtils.hasFineLocationPermissions(context)) {
          LocationUtils.getAccurateLocation(fusedLocationClient)
        } else if (PermissionUtils.hasCoarseLocationPermissions(context)) {
          LocationUtils.getApproximateLocation(fusedLocationClient)
        } else {
          null
        }
      retrievedLocation?.let {
        protoDataStore.writeLocationCoordinates(
          LocationCoordinate(it.latitude, it.longitude, it.altitude, Instant.now()),
        )
      }
      if (retrievedLocation == null) {
        this@AppMainActivity.showToast("Failed to get GPS location", Toast.LENGTH_LONG)
      }
    }
  }

  override fun onSync(syncJobStatus: CurrentSyncJobStatus) {
    when (syncJobStatus) {
      is CurrentSyncJobStatus.Succeeded -> {
        appMainViewModel.run {
          onEvent(
            AppMainEvent.UpdateSyncState(
              state = syncJobStatus,
              lastSyncTime = formatLastSyncTimestamp(syncJobStatus.timestamp),
            ),
          )
        }
      }
      is CurrentSyncJobStatus.Failed -> {
        appMainViewModel.run {
          onEvent(
            AppMainEvent.UpdateSyncState(
              state = syncJobStatus,
              lastSyncTime = formatLastSyncTimestamp(syncJobStatus.timestamp),
            ),
          )
        }
      }
      else -> {
        // Do nothing
      }
    }
  }
}
