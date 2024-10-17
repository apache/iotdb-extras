package v1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// ConfigNodeSpec defines the desired state of ConfigNode
type ConfigNodeSpec struct {
	// Image is the Docker image for the IoTDB instance
	Image string `json:"image"`

	// Replicas is the number of instances to deploy
	Replicas int `json:"replicas"`

	// Resources defines the compute resources (requests/limits)
	Resources ResourceRequirements `json:"resources"`

	Envs map[string]string `json:"envs,omitempty"`

	// Service defines the Kubernetes Service to be created
	Service *ServiceSpec `json:"service,omitempty"`

	// VolumeClaimTemplates allow the creation of persistent volume claims
	VolumeClaimTemplates []VolumeClaimTemplate `json:"volumeClaimTemplates"`
}

// ConfigNodeStatus defines the observed state of ConfigNode
type ConfigNodeStatus struct {
	// INSERT ADDITIONAL STATUS FIELD - define observed state of cluster
}

//+kubebuilder:object:root=true
//+kubebuilder:subresource:status

// ConfigNode is the Schema for the confignodes API
type ConfigNode struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   ConfigNodeSpec   `json:"spec,omitempty"`
	Status ConfigNodeStatus `json:"status,omitempty"`
}

//+kubebuilder:object:root=true

// ConfigNodeList contains a list of ConfigNode
type ConfigNodeList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []ConfigNode `json:"items"`
}

func init() {
	SchemeBuilder.Register(&ConfigNode{}, &ConfigNodeList{})
}
