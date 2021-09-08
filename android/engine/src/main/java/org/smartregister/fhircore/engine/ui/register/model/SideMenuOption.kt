/*
 * Copyright 2021 Ona Systems, Inc
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

package org.smartregister.fhircore.engine.ui.register.model

import android.graphics.drawable.Drawable
import com.google.android.fhir.search.Search
import org.hl7.fhir.r4.model.Patient

/**
 * @property itemId Unique number used to identify the menu item.
 * @property titleResource Android translatable string resource used as the menu option title
 * @property iconResource Android drawable resource used as icon for menu option
 * @property opensMainRegister Use current option to open the main the register
 * @property count The current count for the menu item. Default is 0
 * @property showCount Show clients count against the menu option
 * @property countForResource Indicate whether the count should be for FHIR resource
 * @property entityTypePatient Indicate whether the resource is Patient. Provide custom count
 * queries for resources other than Patient
 * @property searchFilterLambda Filter applied to the the counted entities. Default to filtering
 * only active Patients
 */
data class SideMenuOption(
  val itemId: Int,
  val titleResource: Int,
  val iconResource: Drawable,
  val opensMainRegister: Boolean = false,
  var count: Long = 0,
  val showCount: Boolean = true,
  val countForResource: Boolean = true,
  val entityTypePatient: Boolean = true,
  val searchFilterLambda: (Search) -> Unit = { search -> search.filter(Patient.ACTIVE, true) }
)