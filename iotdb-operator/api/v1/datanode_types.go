/*
Copyright 2024 luke.miao.

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

import (
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// DataNodeSpec defines the desired state of DataNode
type DataNodeSpec struct {
	// Image is the Docker image for the IoTDB instance
	Image string `json:"image"`

	// Replicas is the number of instances to deploy
	Replicas int `json:"replicas"`

	// Resources defines the compute resources (requests/limits)
	Resources corev1.ResourceRequirements `json:"resources"`

	Envs map[string]string `json:"envs,omitempty"`

	// Service defines the Kubernetes Service to be created
	Service *ServiceSpec `json:"service,omitempty"`

	// VolumeClaimTemplates allow the creation of persistent volume claims
	VolumeClaimTemplate corev1.PersistentVolumeClaimSpec `json:"volumeClaimTemplate"`
}

// DataNodeStatus defines the observed state of DataNode
type DataNodeStatus struct {
	// INSERT ADDITIONAL STATUS FIELD - define observed state of cluster
	// Important: Run "make" to regenerate code after modifying this file
}

//+kubebuilder:object:root=true
//+kubebuilder:subresource:status

// DataNode is the Schema for the datanodes API
type DataNode struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   DataNodeSpec   `json:"spec,omitempty"`
	Status DataNodeStatus `json:"status,omitempty"`
}

//+kubebuilder:object:root=true

// DataNodeList contains a list of DataNode
type DataNodeList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []DataNode `json:"items"`
}

func init() {
	SchemeBuilder.Register(&DataNode{}, &DataNodeList{})
}
