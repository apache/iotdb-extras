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

// DataNodeReconciler reconciles a DataNode object
type DataNodeReconciler struct {
	client.Client
	Scheme *runtime.Scheme
}

//+kubebuilder:rbac:groups=iotdb.apache.org,resources=datanodes,verbs=get;list;watch;create;update;patch;delete
//+kubebuilder:rbac:groups=iotdb.apache.org,resources=datanodes/status,verbs=get;update;patch
//+kubebuilder:rbac:groups=iotdb.apache.org,resources=datanodes/finalizers,verbs=update

// Reconcile is part of the main kubernetes reconciliation loop which aims to
// move the current state of the cluster closer to the desired state.
// TODO(user): Modify the Reconcile function to compare the state specified by
// the DataNode object against the actual cluster state, and then
// perform operations to make the cluster state reflect the state specified by
// the user.
//
// For more details, check Reconcile and its Result here:
// - https://pkg.go.dev/sigs.k8s.io/controller-runtime@v0.17.3/pkg/reconcile
func (r *DataNodeReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	logger := log.FromContext(ctx)

	var dataNode iotdbv1.DataNode
	if err := r.Get(ctx, req.NamespacedName, &dataNode); err != nil {
		if errors.IsNotFound(err) {
			logger.Info("DataNode resource not found. May have been deleted.")
			return ctrl.Result{}, nil
		}
		logger.Error(err, "Failed to get IoTDB DataNode")
		return ctrl.Result{}, err
	}

	// Ensure the service exists
	services, err := r.constructServiceForDataNode(&dataNode)
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
		if err := r.Get(ctx, types.NamespacedName{Name: dataNode.Name, Namespace: dataNode.Namespace}, current); err != nil {
			if errors.IsNotFound(err) {
				statefulset := r.constructStateFulSetForDataNode(&dataNode)
				return r.Create(ctx, statefulset)
			}
			return err
		}

		updatedStatefulset := r.constructStateFulSetForDataNode(&dataNode)
		if !reflect.DeepEqual(current.Spec, updatedStatefulset.Spec) {
			return r.Update(ctx, updatedStatefulset)
		}
		return nil
	})

	if err != nil {
		logger.Error(err, "Failed to update Statefulset for IoTDB DataNode")
		return ctrl.Result{}, err
	}

	return ctrl.Result{}, nil
}

func (r *DataNodeReconciler) constructStateFulSetForDataNode(dataNode *iotdbv1.DataNode) *appsv1.StatefulSet {
	labels := map[string]string{"app": DataNodeName}
	replicas := int32(dataNode.Spec.Replicas)
	envVars := make([]corev1.EnvVar, 3)
	envNum := 0
	if dataNode.Spec.Envs != nil {
		envNum = len(dataNode.Spec.Envs)
		envVars = make([]corev1.EnvVar, len(dataNode.Spec.Envs)+3)
		i := 0
		for key, value := range dataNode.Spec.Envs {
			if key == "dn_rpc_port" {
				value = "6667"
			} else if key == "dn_internal_port" {
				value = "10730"
			} else if key == "dn_mpp_data_exchange_port" {
				value = "10740"
			} else if key == "dn_schema_region_consensus_port" {
				value = "10750"
			} else if key == "dn_data_region_consensus_port" {
				value = "10760"
			} else if key == "dn_metric_prometheus_reporter_port" {
				value = "9092"
			} else if key == "rest_service_port" {
				value = "18080"
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
	val1 := ConfigNodeName + "-0." + ConfigNodeName + "-headless." + dataNode.Namespace + ".svc.cluster.local:10710"
	val2 := "$(POD_NAME)." + DataNodeName + "-headless." + dataNode.Namespace + ".svc.cluster.local"
	envVars[envNum+1] = corev1.EnvVar{Name: "dn_seed_config_node", Value: val1}
	envVars[envNum+2] = corev1.EnvVar{Name: "dn_internal_address", Value: val2}

	if len(dataNode.Spec.VolumeClaimTemplates) == 1 {
		volumeClaimTemplates := make([]corev1.PersistentVolumeClaim, len(dataNode.Spec.VolumeClaimTemplates))
		pvcTemplate := dataNode.Spec.VolumeClaimTemplates[0]
		pvcName := pvcTemplate.Spec.Name
		volumeClaimTemplates[0] = *r.constructPVCForDataNode(dataNode, pvcTemplate)
		statefulset := &appsv1.StatefulSet{
			ObjectMeta: metav1.ObjectMeta{
				Name:      DataNodeName,
				Namespace: dataNode.Namespace,
				Labels:    labels,
			},
			Spec: appsv1.StatefulSetSpec{
				Replicas: &replicas,
				Selector: &metav1.LabelSelector{
					MatchLabels: labels,
				},
				ServiceName: DataNodeName + "-headless",
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
								Name:            DataNodeName,
								Image:           dataNode.Spec.Image,
								ImagePullPolicy: corev1.PullIfNotPresent,
								Ports: []corev1.ContainerPort{
									{Name: "rpc-port", ContainerPort: 6667},
									{Name: "internal-port", ContainerPort: 10730},
									{Name: "exchange-port", ContainerPort: 10740},
									{Name: "schema-port", ContainerPort: 10750},
									{Name: "data-port", ContainerPort: 10760},
									{Name: "rest-port", ContainerPort: 18080},
									{Name: "metric-port", ContainerPort: 9092},
								},
								Resources: corev1.ResourceRequirements{
									Limits: corev1.ResourceList{
										corev1.ResourceCPU:    resource.MustParse(dataNode.Spec.Resources.Limits.CPU),
										corev1.ResourceMemory: resource.MustParse(dataNode.Spec.Resources.Limits.Memory),
									},
									Requests: corev1.ResourceList{
										corev1.ResourceCPU:    resource.MustParse(dataNode.Spec.Resources.Limits.CPU),
										corev1.ResourceMemory: resource.MustParse(dataNode.Spec.Resources.Limits.Memory),
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
		controllerutil.SetControllerReference(dataNode, statefulset, r.Scheme)
		return statefulset
	} else {
		//todo add check
	}
	return nil
}

func (r *DataNodeReconciler) constructServiceForDataNode(dataNode *iotdbv1.DataNode) ([]corev1.Service, error) {
	// 创建Headless Service
	headlessService := &corev1.Service{
		ObjectMeta: metav1.ObjectMeta{
			Name:      DataNodeName + "-headless",
			Namespace: dataNode.Namespace,
			Labels:    map[string]string{"app": DataNodeName},
		},
		Spec: corev1.ServiceSpec{
			ClusterIP: "None",
			Ports: []corev1.ServicePort{
				{
					Name:       "dn-internal-port",
					Port:       10730,
					TargetPort: intstr.FromInt32(10730),
				},
				{
					Name:       "dn-mpp-data-exchange-port",
					Port:       10740,
					TargetPort: intstr.FromInt32(10750),
				},
				{
					Name:       "dn-data-region-consensus-port",
					Port:       10760,
					TargetPort: intstr.FromInt32(10760),
				},
				{
					Name:       "dn-schema-region-consensus-port",
					Port:       10750,
					TargetPort: intstr.FromInt32(10750),
				},
				{
					Name:       "dn-rpc-port",
					Port:       6667,
					TargetPort: intstr.FromInt32(6667),
				},
				{
					Name:       "rest-service-port",
					Port:       18080,
					TargetPort: intstr.FromInt32(18080),
				},
				{
					Name:       "dn-metric-prometheus-reporter-port",
					Port:       9092,
					TargetPort: intstr.FromInt32(9092),
				},
			},
			Selector: map[string]string{
				"app": DataNodeName,
			},
		},
	}
	controllerutil.SetControllerReference(dataNode, headlessService, r.Scheme)

	services := []corev1.Service{*headlessService}

	if dataNode.Spec.Service != nil && len(dataNode.Spec.Service.Ports) > 0 {
		ports := make([]corev1.ServicePort, len(dataNode.Spec.Service.Ports))
		i := 0
		for key, value := range dataNode.Spec.Service.Ports {
			port := value
			if key == "dn_metric_prometheus_reporter_port" {
				port = 9092
				ports[i] = corev1.ServicePort{
					Name:       strutil.ToKebabCase(key),
					Port:       port,
					NodePort:   value,
					TargetPort: intstr.FromInt32(port),
				}
				i++
			} else if key == "rest_service_port" {
				port = 18080
				ports[i] = corev1.ServicePort{
					Name:       strutil.ToKebabCase(key),
					Port:       port,
					NodePort:   value,
					TargetPort: intstr.FromInt32(port),
				}
				i++
			} else if key == "dn_rpc_port" {
				port = 6667
				ports[i] = corev1.ServicePort{
					Name:       strutil.ToKebabCase(key),
					Port:       port,
					NodePort:   value,
					TargetPort: intstr.FromInt32(port),
				}
				i++
			}
		}
		if i > 0 {
			nodePorts := ports[0:i]
			nodePortService := &corev1.Service{
				ObjectMeta: metav1.ObjectMeta{
					Name:      DataNodeName,
					Namespace: dataNode.Namespace,
					Labels:    map[string]string{"app": DataNodeName},
				},
				Spec: corev1.ServiceSpec{
					Type:  corev1.ServiceType(dataNode.Spec.Service.Type),
					Ports: nodePorts,
					Selector: map[string]string{
						"app": DataNodeName,
					},
				},
			}
			controllerutil.SetControllerReference(dataNode, nodePortService, r.Scheme)
			services = append(services, *nodePortService)
		}
	}
	return services, nil
}

func (r *DataNodeReconciler) constructPVCForDataNode(dataNode *iotdbv1.DataNode, template iotdbv1.VolumeClaimTemplate) *corev1.PersistentVolumeClaim {
	pvcName := template.Spec.Name
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
			Namespace: dataNode.Namespace,
			Labels:    map[string]string{"app": DataNodeName},
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
	controllerutil.SetControllerReference(dataNode, pvc, r.Scheme)
	return pvc
}

// SetupWithManager sets up the controller with the Manager.
func (r *DataNodeReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&iotdbv1.DataNode{}).
		Complete(r)
}
