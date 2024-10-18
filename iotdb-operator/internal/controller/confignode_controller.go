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

package controller

import (
	"context"
	"github.com/apache/iotdb-operator/internal/controller/strutil"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/intstr"
	"k8s.io/client-go/util/retry"
	"reflect"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"

	"k8s.io/apimachinery/pkg/runtime"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/log"

	iotdbv1 "github.com/apache/iotdb-operator/api/v1"
)

// ConfigNodeReconciler reconciles a ConfigNode object
type ConfigNodeReconciler struct {
	client.Client
	Scheme *runtime.Scheme
}

// +kubebuilder:rbac:groups=iotdb.apache.org,resources=confignodes,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=iotdb.apache.org,resources=confignodes/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=iotdb.apache.org,resources=confignodes/finalizers,verbs=update
// +kubebuilder:rbac:groups="",resources=services,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups="",resources=persistentvolumeclaims,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=apps,resources=statefulsets,verbs=get;list;watch;create;update;patch;delete

// Reconcile function compares the state specified by the ConfigNode object against the actual cluster state.
func (r *ConfigNodeReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	logger := log.FromContext(ctx)

	var configNode iotdbv1.ConfigNode
	if err := r.Get(ctx, req.NamespacedName, &configNode); err != nil {
		if errors.IsNotFound(err) {
			logger.Info("ConfigNode resource not found. May have been deleted.")
			return ctrl.Result{}, nil
		}
		logger.Error(err, "Failed to get IoTDB ConfigNode")
		return ctrl.Result{}, err
	}

	// Ensure the service exists
	services, err := r.constructServicesForConfigNode(&configNode)
	if err != nil {
		return ctrl.Result{}, err
	}
	for _, service := range services {
		existingService := &corev1.Service{}
		err := r.Get(ctx, types.NamespacedName{Name: service.Name, Namespace: service.Namespace}, existingService)
		if err != nil && errors.IsNotFound(err) {
			if err := r.Create(ctx, &service); err != nil {
				return ctrl.Result{}, err
			}
		} else if err != nil {
			return ctrl.Result{}, err
		}

		// Ensure the service is up-to-date
		if !reflect.DeepEqual(existingService, service) {
			if err := r.Update(ctx, &service); err != nil {
				return ctrl.Result{}, err
			}
		}
	}

	// Ensure StatefulSet exists and is up-to-date
	err = retry.RetryOnConflict(retry.DefaultRetry, func() error {
		current := &appsv1.StatefulSet{}
		if err := r.Get(ctx, types.NamespacedName{Name: configNode.Name, Namespace: configNode.Namespace}, current); err != nil {
			if errors.IsNotFound(err) {
				statefulset := r.constructStateFulSetForConfigNode(&configNode)
				return r.Create(ctx, statefulset)
			}
			return err
		}

		updatedStatefulset := r.constructStateFulSetForConfigNode(&configNode)
		if !reflect.DeepEqual(current.Spec, updatedStatefulset.Spec) {
			return r.Update(ctx, updatedStatefulset)
		}
		return nil
	})

	if err != nil {
		logger.Error(err, "Failed to update Statefulset for IoTDB ConfigNode")
		return ctrl.Result{}, err
	}

	return ctrl.Result{}, nil
}

func (r *ConfigNodeReconciler) constructStateFulSetForConfigNode(configNode *iotdbv1.ConfigNode) *appsv1.StatefulSet {
	labels := map[string]string{"app": ConfigNodeName}
	replicas := int32(configNode.Spec.Replicas)
	envVars := make([]corev1.EnvVar, 3)
	envNum := 0
	if configNode.Spec.Envs != nil {
		envNum = len(configNode.Spec.Envs)
		envVars = make([]corev1.EnvVar, len(configNode.Spec.Envs)+3)
		i := 0
		for key, value := range configNode.Spec.Envs {
			if key == "cn_internal_port" {
				value = "10710"
			} else if key == "cn_consensus_port" {
				value = "10720"
			} else if key == "cn_metric_prometheus_reporter_port" {
				value = "9091"
			}
			envVars[i] = corev1.EnvVar{Name: key, Value: value}
			i++
		}
	}

	envVars[envNum] = corev1.EnvVar{
		Name: "POD_NAME",
		ValueFrom: &corev1.EnvVarSource{
			FieldRef: &corev1.ObjectFieldSelector{
				FieldPath: "metadata.name",
			},
		},
	}
	val1 := ConfigNodeName + "-0." + ConfigNodeName + "-headless." + configNode.Namespace + ".svc.cluster.local:10710"
	val2 := "$(POD_NAME)." + ConfigNodeName + "-headless." + configNode.Namespace + ".svc.cluster.local"
	envVars[envNum+1] = corev1.EnvVar{Name: "cn_seed_config_node", Value: val1}
	envVars[envNum+2] = corev1.EnvVar{Name: "cn_internal_address", Value: val2}

	if len(configNode.Spec.VolumeClaimTemplates) == 1 {
		volumeClaimTemplates := make([]corev1.PersistentVolumeClaim, len(configNode.Spec.VolumeClaimTemplates))
		pvcTemplate := configNode.Spec.VolumeClaimTemplates[0]
		pvcName := pvcTemplate.Spec.Name
		volumeClaimTemplates[0] = *r.constructPVCForConfigNode(configNode, pvcTemplate, pvcName)
		statefulset := &appsv1.StatefulSet{
			ObjectMeta: metav1.ObjectMeta{
				Name:      ConfigNodeName,
				Namespace: configNode.Namespace,
				Labels:    labels,
			},
			Spec: appsv1.StatefulSetSpec{
				Replicas: &replicas,
				Selector: &metav1.LabelSelector{
					MatchLabels: labels,
				},
				ServiceName: ConfigNodeName + "-headless",
				Template: corev1.PodTemplateSpec{
					ObjectMeta: metav1.ObjectMeta{
						Labels: labels,
					},
					Spec: corev1.PodSpec{
						Affinity: &corev1.Affinity{
							PodAntiAffinity: &corev1.PodAntiAffinity{
								RequiredDuringSchedulingIgnoredDuringExecution: []corev1.PodAffinityTerm{
									{
										LabelSelector: &metav1.LabelSelector{
											MatchLabels: labels,
										},
										TopologyKey: "kubernetes.io/hostname",
									},
								},
							},
						},
						Containers: []corev1.Container{
							{
								Name:            ConfigNodeName,
								Image:           configNode.Spec.Image,
								ImagePullPolicy: corev1.PullIfNotPresent,
								Ports: []corev1.ContainerPort{
									{Name: "internal", ContainerPort: 10710},
									{Name: "consensus", ContainerPort: 10720},
								},
								Resources: corev1.ResourceRequirements{
									Limits: corev1.ResourceList{
										corev1.ResourceCPU:    resource.MustParse(configNode.Spec.Resources.Limits.CPU),
										corev1.ResourceMemory: resource.MustParse(configNode.Spec.Resources.Limits.Memory),
									},
									Requests: corev1.ResourceList{
										corev1.ResourceCPU:    resource.MustParse(configNode.Spec.Resources.Limits.CPU),
										corev1.ResourceMemory: resource.MustParse(configNode.Spec.Resources.Limits.Memory),
									},
								},
								Env: envVars,
								VolumeMounts: []corev1.VolumeMount{
									{Name: pvcName, MountPath: "/iotdb/data", SubPath: "data"},
									{Name: pvcName, MountPath: "/iotdb/logs", SubPath: "logs"},
									{Name: pvcName, MountPath: "/iotdb/ext", SubPath: "ext"},
									{Name: pvcName, MountPath: "/iotdb/.env", SubPath: ".env"},
									{Name: pvcName, MountPath: "/iotdb/activation", SubPath: "activation"},
								},
							},
						},
					},
				},
				VolumeClaimTemplates: volumeClaimTemplates,
			},
		}
		controllerutil.SetControllerReference(configNode, statefulset, r.Scheme)
		return statefulset
	} else {
		//todo add check
	}
	return nil
}

func (r *ConfigNodeReconciler) constructServicesForConfigNode(configNode *iotdbv1.ConfigNode) ([]corev1.Service, error) {
	// 创建Headless Service
	headlessService := &corev1.Service{
		ObjectMeta: metav1.ObjectMeta{
			Name:      ConfigNodeName + "-headless",
			Namespace: configNode.Namespace,
			Labels:    map[string]string{"app": ConfigNodeName},
		},
		Spec: corev1.ServiceSpec{
			ClusterIP: "None",
			Ports: []corev1.ServicePort{
				{
					Name:       "cn-internal-port",
					Port:       10710,
					TargetPort: intstr.FromInt32(10710),
				},
				{
					Name:       "cn-consensus-port",
					Port:       10720,
					TargetPort: intstr.FromInt32(10720),
				},
			},
			Selector: map[string]string{
				"app": ConfigNodeName,
			},
		},
	}
	controllerutil.SetControllerReference(configNode, headlessService, r.Scheme)

	services := []corev1.Service{*headlessService}

	// 检查是否需要创建NodePort Service
	if configNode.Spec.Service != nil && len(configNode.Spec.Service.Ports) > 0 {
		for key, value := range configNode.Spec.Service.Ports {
			if key == "cn_metric_prometheus_reporter_port" {
				// 创建NodePort Service
				nodePortService := &corev1.Service{
					ObjectMeta: metav1.ObjectMeta{
						Name:      ConfigNodeName,
						Namespace: configNode.Namespace,
						Labels:    map[string]string{"app": ConfigNodeName},
					},
					Spec: corev1.ServiceSpec{
						Type: corev1.ServiceTypeNodePort,
						Ports: []corev1.ServicePort{
							{
								Name:       strutil.ToKebabCase(key),
								Port:       9091,
								NodePort:   value,
								TargetPort: intstr.FromInt32(9091),
							},
						},
						Selector: map[string]string{
							"app": ConfigNodeName,
						},
					},
				}
				controllerutil.SetControllerReference(configNode, nodePortService, r.Scheme)
				services = append(services, *nodePortService)
			}
		}
	}

	return services, nil
}

func (r *ConfigNodeReconciler) constructPVCForConfigNode(configNode *iotdbv1.ConfigNode, template iotdbv1.VolumeClaimTemplate, pvcName string) *corev1.PersistentVolumeClaim {
	accessModes := make([]corev1.PersistentVolumeAccessMode, len(template.Spec.AccessModes))
	for i, mode := range template.Spec.AccessModes {
		accessModes[i] = corev1.PersistentVolumeAccessMode(mode)
	}

	resourceRequirements := corev1.VolumeResourceRequirements{
		Requests: corev1.ResourceList{
			corev1.ResourceStorage: resource.MustParse(template.Spec.Resources.Requests.Storage),
		},
	}
	pvc := &corev1.PersistentVolumeClaim{
		ObjectMeta: metav1.ObjectMeta{
			Name:      pvcName,
			Namespace: configNode.Namespace,
			Labels:    map[string]string{"app": ConfigNodeName},
		},
		Spec: corev1.PersistentVolumeClaimSpec{
			AccessModes:      accessModes,
			Resources:        resourceRequirements,
			StorageClassName: &template.Spec.StorageClassName,
			Selector: &metav1.LabelSelector{
				MatchLabels: map[string]string{"app": pvcName},
			},
		},
	}
	controllerutil.SetControllerReference(configNode, pvc, r.Scheme)
	return pvc
}

// SetupWithManager sets up the controller with the Manager.
func (r *ConfigNodeReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&iotdbv1.ConfigNode{}).
		Owns(&corev1.Service{}).
		Owns(&corev1.PersistentVolumeClaim{}).
		Complete(r)
}
