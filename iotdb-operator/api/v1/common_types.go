/*
Copyright 2024.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package v1

// ServiceSpec defines the attributes that determine how the Service is created
type ServiceSpec struct {
	// Type determines how the Service is exposed
	Type string `json:"type"`

	// Ports are the map of Service ports
	Ports map[string]int32 `json:"ports"`
}

// VolumeClaimTemplate specifies data volume specifications of the storage.
type VolumeClaimTemplate struct {

	// Spec defines the desired characteristics of a volume requested by a pod author.
	Spec PersistentVolumeClaimSpec `json:"spec"`
}

// PersistentVolumeClaimSpec describes the common attributes of storage devices
// and allows a Source for provider-specific attributes
type PersistentVolumeClaimSpec struct {
	// AccessModes contains all ways the volume can be mounted
	Name        string   `json:"name"`
	AccessModes []string `json:"accessModes"`

	// A label query over volumes to consider for binding.
	StorageClassName string `json:"storageClassName"`

	// Resources represents the minimum resources required
	Resources PVCResourceRequirements `json:"resources"`
}

// ResourceRequirements describes the compute resource requirements.
type ResourceRequirements struct {
	// Requests is the amount of compute resources required.
	Requests ResourceList `json:"requests"`

	// Limits is the upper limit of resources
	Limits ResourceList `json:"limits"`
}

type PVCResourceRequirements struct {
	// Requests is the amount of compute resources required.
	Requests Storage `json:"requests"`
}

type Storage struct {
	// Requests is the amount of compute resources required.
	Storage string `json:"storage"`
}

// ResourceList defines a list of requested and limited resources.
type ResourceList struct {
	// Memory requested or limited in the format (e.g., 1Gi)
	Memory string `json:"memory"`

	// CPU requested or limited in the format (e.g., 500m)
	CPU string `json:"cpu"`
}
