package io.openems.edge.core.appmanager.dependency;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.component.annotations.ServiceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.gson.JsonObject;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.function.ThrowingSupplier;
import io.openems.common.session.Language;
import io.openems.common.types.EdgeConfig;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.user.User;
import io.openems.edge.core.appmanager.AppConfiguration;
import io.openems.edge.core.appmanager.AppManager;
import io.openems.edge.core.appmanager.AppManagerImpl;
import io.openems.edge.core.appmanager.AppManagerUtil;
import io.openems.edge.core.appmanager.ComponentUtil;
import io.openems.edge.core.appmanager.ComponentUtilImpl;
import io.openems.edge.core.appmanager.ConfigurationTarget;
import io.openems.edge.core.appmanager.InterfaceConfiguration;
import io.openems.edge.core.appmanager.OpenemsApp;
import io.openems.edge.core.appmanager.OpenemsAppInstance;
import io.openems.edge.core.appmanager.TranslationUtil;
import io.openems.edge.core.appmanager.dependency.DependencyDeclaration.AppDependencyConfig;
import io.openems.edge.core.appmanager.validator.Validator;

@Component(//
		immediate = true, //
		scope = ServiceScope.SINGLETON //
)
public class AppManagerAppHelperImpl implements AppManagerAppHelper {

	private final Logger log = LoggerFactory.getLogger(this.getClass());

	@Reference(//
			policy = ReferencePolicy.DYNAMIC, //
			policyOption = ReferencePolicyOption.GREEDY, //
			cardinality = ReferenceCardinality.OPTIONAL //
	)
	private volatile AppManager appManager;

	@Reference(//
			policy = ReferencePolicy.DYNAMIC, //
			policyOption = ReferencePolicyOption.GREEDY, //
			cardinality = ReferenceCardinality.OPTIONAL //
	)
	private volatile AppManagerUtil appManagerUtil;

	private final ComponentManager componentManager;
	private final ComponentUtil componentUtil;

	private final Validator validator;

	// tasks
	private final AggregateTask.ComponentAggregateTask componentsTask;
	private final AggregateTask.SchedulerAggregateTask schedulerTask;
	private final AggregateTask.StaticIpAggregateTask staticIpTask;

	private final AggregateTask[] tasks;

	private TemporaryApps temporaryApps;

	@Activate
	public AppManagerAppHelperImpl(//
			@Reference ComponentManager componentManager, //
			@Reference ComponentUtil componentUtil, //
			@Reference Validator validator, //
			@Reference AggregateTask.ComponentAggregateTask componentsTask, //
			@Reference AggregateTask.SchedulerAggregateTask schedulerTask, //
			@Reference AggregateTask.StaticIpAggregateTask staticIpTask //
	) {
		this.componentManager = componentManager;
		this.componentUtil = componentUtil;
		this.validator = validator;
		this.componentsTask = componentsTask;
		this.schedulerTask = schedulerTask;
		this.staticIpTask = staticIpTask;
		this.tasks = new AggregateTask[] { componentsTask, schedulerTask, staticIpTask };
	}

	@Override
	public UpdateValues installApp(User user, OpenemsAppInstance instance, OpenemsApp app)
			throws OpenemsNamedException {
		return this.updateApp(user, null, instance, app);
	}

	@Override
	public UpdateValues updateApp(User user, OpenemsAppInstance oldInstance, OpenemsAppInstance instance,
			OpenemsApp app) throws OpenemsNamedException {
		return this.usingTemporaryApps(user, () -> this.updateAppInternal(user, oldInstance, instance, app));
	}

	@Override
	public UpdateValues deleteApp(User user, OpenemsAppInstance instance) throws OpenemsNamedException {
		return this.usingTemporaryApps(user, () -> this.deleteAppInternal(user, instance));
	}

	private UpdateValues usingTemporaryApps(User user, ThrowingSupplier<UpdateValues, OpenemsNamedException> supplier)
			throws OpenemsNamedException {
		Objects.requireNonNull(supplier);
		// to make sure the temporaryApps get set to null
		this.resetTasks();
		this.temporaryApps = new TemporaryApps();
		OpenemsNamedException exception = null;
		RuntimeException runtimeException = null;
		UpdateValues result = null;
		try {
			result = supplier.get();
		} catch (OpenemsNamedException e) {
			exception = e;
		} catch (RuntimeException e) {
			runtimeException = e;
		}
		final var tempTemporarayApps = this.temporaryApps;
		this.temporaryApps = null;
		if (exception != null) {
			this.log.error("An Exception occurred during handling the supplier.", exception);
			throw exception;
		}
		if (runtimeException != null) {
			this.log.error("An RuntimeException occurred during handling the supplier.", runtimeException);
			throw runtimeException;
		}

		var ignoreInstances = new ArrayList<OpenemsAppInstance>(tempTemporarayApps.currentlyModifiedApps().size() //
				+ tempTemporarayApps.currentlyDeletingApps().size());
		ignoreInstances.addAll(tempTemporarayApps.currentlyModifiedApps());
		ignoreInstances.addAll(tempTemporarayApps.currentlyDeletingApps());

		var otherAppConfigs = this.getAppManagerImpl()
				.getOtherAppConfigurations(ignoreInstances.stream().map(t -> t.instanceId).toArray(UUID[]::new));

		var errors = new LinkedList<String>();
		final var language = user == null ? null : user.getLanguage();
		final var bundle = getTranslationBundle(language);

		// execute all tasks
		Lists.newArrayList(//
				Map.entry(this.componentsTask, "canNotUpdateComponents"), //
				// needs to run after component task to get the components which were created
				Map.entry(this.schedulerTask, "canNotUpdateScheduler"), //
				Map.entry(this.staticIpTask, "canNotUpdateStaticIps")) //
				.forEach(entry -> {
					try {
						entry.getKey().create(user, otherAppConfigs);
					} catch (OpenemsNamedException e) {
						final var errorMessage = TranslationUtil.getTranslation(bundle, entry.getValue());
						this.log.error(errorMessage, e);
						errors.add(errorMessage);
					}
				});

		if (!errors.isEmpty()) {
			throw new OpenemsException(errors.stream().collect(Collectors.joining("|")));
		}

		return result;
	}

	private UpdateValues updateAppInternal(final User user, OpenemsAppInstance oldInstance,
			OpenemsAppInstance newInstance, final OpenemsApp app) throws OpenemsNamedException {
		final var language = user == null ? null : user.getLanguage();

		final var warnings = new LinkedList<String>();
		final var bundle = getTranslationBundle(language);
		final var toCreateInstances = new ArrayList<OpenemsAppInstance>();
		if (oldInstance == null) {
			// TODO maybe check for all apps and its dependencies
			this.checkStatus(app, language);
			this.temporaryApps.currentlyCreatingApps().add(newInstance);
			toCreateInstances.add(newInstance);
		} else {
			// determine if properties are allowed to be updated
			var references = this.getAppsWithReferenceTo(oldInstance.instanceId);
			references.removeAll(this.temporaryApps.currentlyDeletingApps());
			for (var entry : this.getAppManagerImpl().appConfigs(references, null)) {
				for (var dependencieDeclaration : entry.getValue().dependencies) {

					var dd = entry.getKey().dependencies.stream()
							.filter(d -> d.instanceId.equals(oldInstance.instanceId))
							.filter(d -> d.key.equals(dependencieDeclaration.key)).findAny();

					if (dd.isEmpty()) {
						continue;
					}

					var dependencyApp = this.appManagerUtil.getInstanceById(dd.get().instanceId);

					var appConfig = this.getAppDependencyConfig(dependencyApp, dependencieDeclaration.appConfigs);

					if (appConfig == null) {
						continue;
					}

					switch (dependencieDeclaration.dependencyUpdatePolicy) {
					case ALLOW_ALL:
						// everything can be changed
						break;
					case ALLOW_NONE:
						throw new OpenemsException(TranslationUtil.getTranslation(bundle, "appNotAllowedToBeUpdated"));
					case ALLOW_ONLY_UNCONFIGURED_PROPERTIES:
						// override properties
						for (var propEntry : appConfig.properties.entrySet()) {
							if (!newInstance.properties.has(propEntry.getKey())
									|| !newInstance.properties.get(propEntry.getKey()).equals(propEntry.getValue())) {

								warnings.add(TranslationUtil.getTranslation(bundle, "canNotChangeProperty",
										propEntry.getKey()));

								newInstance.properties.add(propEntry.getKey(), propEntry.getValue());
							}

						}
						// override alias if set from a parent dependency
						if (appConfig.alias != null && !newInstance.alias.equals(appConfig.alias)) {
							warnings.add(TranslationUtil.getTranslation(bundle, "canNotChangeAlias"));
							newInstance = new OpenemsAppInstance(newInstance.appId, appConfig.alias,
									newInstance.instanceId, newInstance.properties, newInstance.dependencies);
						}
						break;
					}
				}
			}
		}

		var errors = new LinkedList<String>();
		// the DependencyConfig of the parent app, the instance of the DependencyConfig
		var dependencieInstances = new HashMap<DependencyConfig, OpenemsAppInstance>();

		var oldInstances = this.getOldDependencies(oldInstance, language);

		final var lastCreatedOrModifiedApp = new AtomicReference<OpenemsAppInstance>();
		// update app and its dependencies
		this.foreachDependency(errors, app, newInstance.alias, newInstance.properties, //
				ConfigurationTarget.UPDATE, language, //
				this::determineDependencyConfig, //
				this.includeDependency(oldInstances, toCreateInstances, language), //
				dc -> {
					// get old instance if existing
					ExistingDependencyConfig oldAppConfig = null;
					if (oldInstance != null) {
						// TODO make sure not the parent is a dependency
						if (dc.isDependency() && oldInstance.appId.equals(app.getAppId())) {
							oldAppConfig = oldInstances.remove(new AppIdKey(dc.parent.getAppId(), dc.sub.key));
							if (oldAppConfig != null) {
								for (var entry : oldAppConfig.appDependencyConfig.properties.entrySet()) {
									// add old values which are not set by the DependencyDeclaration
									if (!dc.appDependencyConfig.properties.has(entry.getKey())) {
										dc.appDependencyConfig.properties.add(entry.getKey(), entry.getValue());
									}
								}
							}
						} else {
							AppConfiguration oldAppConfiguration = null;
							try {
								oldAppConfiguration = this.appManagerUtil
										.getAppConfiguration(ConfigurationTarget.UPDATE, dc.app, oldInstance, language);

							} catch (OpenemsNamedException e) {
								this.log.error(e.getMessage());
							}
							var appDependencyConfig = DependencyDeclaration.AppDependencyConfig.create() //
									.setAppId(app.getAppId()) //
									.setAlias(oldInstance.alias) //
									.setProperties(oldInstance.properties) //
									.build();
							oldAppConfig = new ExistingDependencyConfig(app, null, null, oldAppConfiguration,
									appDependencyConfig, null, null, oldInstance);
						}
					}

					// map dependencies if this is the parent
					List<Dependency> dependencies = new ArrayList<>(dependencieInstances.size());
					var removeKeys = new LinkedList<DependencyConfig>();
					for (var dependency : dependencieInstances.entrySet()) {
						if (!dc.config.dependencies.stream().anyMatch(t -> t.equals(dependency.getKey().sub))) {
							continue;
						}
						removeKeys.add(dependency.getKey());
						dependencies.add(new Dependency(dependency.getKey().sub.key, dependency.getValue().instanceId));
					}
					for (var removeKey : removeKeys) {
						dependencieInstances.remove(removeKey);
					}

					// create app or get as dependency
					if (oldAppConfig == null) {
						var neededApp = this.findNeededApp(dc.sub, dc.appDependencyConfig);
						if (neededApp == null) {
							return false;
						}
						AppConfiguration oldConfig = null;
						UUID instanceId;
						OpenemsAppInstance oldInstanceOfCurrentApp = null;
						JsonObject propertiesOfNewInstance;
						var aliasOfNewInstance = dc.appDependencyConfig.alias;
						if (neededApp.isPresent()
								&& !toCreateInstances.stream().anyMatch(t -> t.equals(neededApp.get()))) {
							oldInstanceOfCurrentApp = neededApp.get();
							instanceId = oldInstanceOfCurrentApp.instanceId;
							if (dc.sub.updatePolicy.isAllowedToUpdate(this.getAppManagerImpl().getInstantiatedApps(),
									null, neededApp.get())) {
								try {
									// update app
									oldConfig = this.appManagerUtil.getAppConfiguration(ConfigurationTarget.UPDATE,
											neededApp.get(), language);
									for (var entry : neededApp.get().properties.entrySet()) {
										// add old values which are not set by the DependecyDeclaration
										if (!dc.appDependencyConfig.properties.has(entry.getKey())) {
											dc.appDependencyConfig.properties.add(entry.getKey(), entry.getValue());
										}
									}

									if (aliasOfNewInstance == null) {
										aliasOfNewInstance = oldInstanceOfCurrentApp.alias;
									}

								} catch (OpenemsNamedException e) {
									this.log.error(e.getMessage());
									errors.add(TranslationUtil.getTranslation(bundle, "canNotGetAppConfiguration"));
								}
								propertiesOfNewInstance = dc.appDependencyConfig.properties;
							} else {
								aliasOfNewInstance = oldInstanceOfCurrentApp.alias;
								dependencies = oldInstanceOfCurrentApp.dependencies;
								propertiesOfNewInstance = oldInstanceOfCurrentApp.properties;
							}

						} else {
							var existing = toCreateInstances.stream() //
									.filter(t -> t.appId.equals(dc.app.getAppId())) //
									.findFirst();
							toCreateInstances.remove(existing.get());
							instanceId = existing.get().instanceId;
							propertiesOfNewInstance = dc.appDependencyConfig.initialProperties;
							// use app name as default alias if not given
							if (aliasOfNewInstance == null) {
								aliasOfNewInstance = dc.app.getName(language);
							}

							// check if the created app can satisfy another app dependency
							final var fallBackAlwaysCreateApp = new AtomicReference<OpenemsAppInstance>();

							List<OpenemsAppInstance> apps2UpdateDependency = this.getAllInstances().stream() //
									.filter(i -> {
										var neededDependency = this.getNeededDependencyTo(i, dc.app.getAppId(),
												instanceId);
										if (neededDependency == null) {
											return false;
										}
										if (neededDependency.createPolicy == DependencyDeclaration.CreatePolicy.ALWAYS) {
											// only set the dependency to one app which has the always create policy
											fallBackAlwaysCreateApp.set(i);
											return false;
										}
										return true;
									}) //
									.collect(Collectors.toList());

							if (apps2UpdateDependency.isEmpty() && fallBackAlwaysCreateApp.get() != null) {
								apps2UpdateDependency.add(fallBackAlwaysCreateApp.get());
							}

							for (var instance : apps2UpdateDependency) {
								var neededDependency = this.getNeededDependencyTo(instance, dc.app.getAppId(),
										instanceId);
								// override properties if set by dependency
								if (neededDependency.dependencyUpdatePolicy != DependencyDeclaration.DependencyUpdatePolicy.ALLOW_ALL) {
									var config = this.determineDependencyConfig(neededDependency.appConfigs);
									for (var entry : config.properties.entrySet()) {
										if (!dc.appDependencyConfig.properties.has(entry.getKey())
												|| !dc.appDependencyConfig.properties.get(entry.getKey())
														.equals(entry.getValue())) {
											warnings.add(TranslationUtil.getTranslation(bundle, "overrideProperty",
													entry.getKey()));
										}
										dc.appDependencyConfig.properties.add(entry.getKey(), entry.getValue());
									}
								}

								// update dependencies
								var replaceApp = instance;
								List<OpenemsAppInstance> modifiedOrCreatedApps = null;
								for (var list : Lists.newArrayList(this.temporaryApps.currentlyCreatingApps(),
										this.temporaryApps.currentlyModifiedApps())) {
									modifiedOrCreatedApps = list;
									var foundReplaceApp = modifiedOrCreatedApps.stream() //
											.filter(i -> i.equals(instance)) //
											.findAny().orElse(null);
									if (foundReplaceApp != null) {
										replaceApp = foundReplaceApp;
										break;
									}
								}

								var newDependencies = new ArrayList<Dependency>();
								if (replaceApp.dependencies != null) {
									newDependencies.addAll(replaceApp.dependencies);
								}
								newDependencies.add(new Dependency(neededDependency.key, instanceId));
								modifiedOrCreatedApps.remove(replaceApp);
								modifiedOrCreatedApps.add(new OpenemsAppInstance(replaceApp.appId, replaceApp.alias,
										replaceApp.instanceId, replaceApp.properties, newDependencies));
							}
						}

						var newAppInstance = new OpenemsAppInstance(dc.app.getAppId(), aliasOfNewInstance, instanceId,
								propertiesOfNewInstance, dependencies);
						lastCreatedOrModifiedApp.set(newAppInstance);
						this.temporaryApps.currentlyModifiedApps().removeIf(t -> t.equals(newAppInstance));
						this.temporaryApps.currentlyCreatingApps().removeIf(t -> t.equals(newAppInstance));
						if (neededApp.isPresent()) {
							this.temporaryApps.currentlyModifiedApps().add(newAppInstance);
						} else {
							this.temporaryApps.currentlyCreatingApps().add(newAppInstance);
						}

						dependencieInstances.put(dc, newAppInstance);
						try {
							var otherAppConfigs = this.getAppManagerImpl()
									.getOtherAppConfigurations(newAppInstance.instanceId);

							// add configurations from currently creating apps
							for (var config : this.getAppManagerImpl().appConfigs(
									this.temporaryApps.currentlyCreatingModifiedApps(),
									AppManagerImpl.excludingInstanceIds(newAppInstance.instanceId))) {
								otherAppConfigs.add(config.getValue());
							}

							var newConfig = this.getNewAppConfigWithReplacedIds(dc.app, oldInstanceOfCurrentApp,
									newAppInstance, AppManagerAppHelperImpl.getComponentsFromConfigs(otherAppConfigs),
									language);
							this.removeNotAllowedToSavedProperties(newAppInstance);

							this.aggregateAllTasks(newConfig, oldConfig);
						} catch (OpenemsNamedException e) {
							this.log.error(e.getMessage());
							errors.add(TranslationUtil.getTranslation(bundle, "canNotGetAppConfiguration"));
						}
						return true;
					}

					var allInstances = this.getAllInstances();
					// add already existing dependencies only if not existing
					for (var dependency : Optional.ofNullable(oldAppConfig.instance.dependencies)
							.orElse(Collections.emptyList())) {
						// check if dependency is not already added
						if (dependencies.stream().anyMatch(d -> d.key.equals(dependency.key))) {
							continue;
						}
						// check if instance still exists
						if (!allInstances.stream().anyMatch(t -> t.instanceId.equals(dependency.instanceId))) {
							continue;
						}
						dependencies.add(dependency);
					}

					// find parent
					OpenemsAppInstance parent = null;
					if (dc.isDependency()) {
						if (dc.parent.getAppId().equals(oldInstance.appId)) {
							parent = oldInstance;
						} else {
							for (var entry : oldInstances.entrySet()) {
								if (entry.getValue().app.equals(dc.parent)) {
									parent = entry.getValue().instance;
									break;
								}
							}
						}
					}

					// update existing app
					var isNotAllowedToUpdate = dc.isDependency()
							&& !dc.sub.updatePolicy.isAllowedToUpdate(this.getAppManagerImpl().getInstantiatedApps(),
									parent, oldAppConfig.instance);

					var newInstanceAlias = Optional.ofNullable(dc.appDependencyConfig.alias)
							.orElse(Optional.ofNullable(oldAppConfig.instance.alias) //
									.orElse(dc.app.getName(language)));

					OpenemsAppInstance newAppInstance;

					if (isNotAllowedToUpdate) {
						newAppInstance = oldAppConfig.instance;
					} else {
						var newInstanceProperties = dc.appDependencyConfig.properties;
						// only add old configuration properties to updated app when it got updated by a
						// parent app. This ensures that a property can be modified by the user on a
						// child app and also not overwrite it when updating the parent app
						// TODO child app may have inconsistent properties
						if (dc.isDependency()) {
							for (var entry : oldAppConfig.instance.properties.entrySet()) {
								if (newInstanceProperties.has(entry.getKey())) {
									continue;
								}
								newInstanceProperties.add(entry.getKey(), entry.getValue());
							}
						}
						newAppInstance = new OpenemsAppInstance(dc.app.getAppId(), newInstanceAlias,
								oldAppConfig.instance.instanceId, newInstanceProperties, dependencies);
					}

					lastCreatedOrModifiedApp.set(newAppInstance);
					dependencieInstances.put(dc, newAppInstance);

					if (isNotAllowedToUpdate) {
						// not allowed to update but still a dependency
						return true;
					}
					this.temporaryApps.currentlyModifiedApps().removeIf(t -> t.equals(newAppInstance));
					this.temporaryApps.currentlyModifiedApps().add(newAppInstance);

					try {
						var otherAppConfigs = this.getAppManagerImpl()
								.getOtherAppConfigurations(newAppInstance.instanceId);

						// add configurations from currently creating apps
						for (var config : this.getAppManagerImpl().appConfigs(
								this.temporaryApps.currentlyCreatingModifiedApps(),
								AppManagerImpl.excludingInstanceIds(newAppInstance.instanceId))) {
							otherAppConfigs.add(config.getValue());
						}

						var newAppConfig = this.getNewAppConfigWithReplacedIds(dc.app, oldAppConfig.instance,
								newAppInstance, AppManagerAppHelperImpl.getComponentsFromConfigs(otherAppConfigs),
								language);

						this.removeNotAllowedToSavedProperties(newAppInstance);

						this.aggregateAllTasks(newAppConfig, oldAppConfig.config);

					} catch (OpenemsNamedException e) {
						this.log.error(e.getMessage());
						errors.add(TranslationUtil.getTranslation(bundle, "canNotGetAppConfiguration"));
					}

					return true;
				});

		// add removed apps for deletion
		for (var entry : oldInstances.entrySet()) {
			var dc = entry.getValue();
			if (!dc.sub.deletePolicy.isAllowedToDelete(this.getAppManagerImpl().getInstantiatedApps(),
					dc.parentInstance, dc.instance)) {
				continue;
			}
			this.aggregateAllTasks(null, dc.config);
			this.temporaryApps.currentlyDeletingApps().add(dc.instance);
		}

		this.updateReferencesToRemovedInstances();

		if (!errors.isEmpty()) {
			throw new OpenemsException(errors.stream().collect(Collectors.joining("|")));
		}

		return new UpdateValues(lastCreatedOrModifiedApp.get(), this.temporaryApps.currentlyCreatingModifiedApps(),
				this.temporaryApps.currentlyDeletingApps(), warnings);
	}

	private Map<AppIdKey, ExistingDependencyConfig> getOldDependencies(//
			final OpenemsAppInstance oldInstance, //
			final Language language //
	) throws OpenemsNamedException {
		if (oldInstance == null) {
			return Collections.emptyMap();
		}
		try {
			final var oldInstances = new TreeMap<AppIdKey, ExistingDependencyConfig>();
			// get all existing app dependencies
			this.foreachExistingDependency(oldInstance, ConfigurationTarget.UPDATE, language, null, dc -> {
				if (!dc.isDependency()) {
					return true;
				}
				oldInstances.put(new AppIdKey(dc.parentInstance, dc.parentInstance.appId, dc.sub.key), dc);
				return true;
			});
			return oldInstances;
		} catch (OpenemsNamedException e) {
			return Collections.emptyMap();
		}
	}

	private BiFunction<OpenemsApp, DependencyDeclaration, IncludeApp> includeDependency(//
			final Map<AppIdKey, ExistingDependencyConfig> oldInstances, //
			final List<OpenemsAppInstance> toCreateInstances, //
			final Language language //
	) {
		return (a, d) -> {

			var oldAppConfig = oldInstances.get(new AppIdKey(a.getAppId(), d.key));

			if (oldAppConfig != null) {
				final var appIdOfExistingInstance = oldAppConfig.instance.appId;
				if (!d.appConfigs.stream() //
						.anyMatch(c -> Objects.equals(c.appId, appIdOfExistingInstance))) {

					// remove dependencies which got not included because the app already exists
					removeAppsWithParent(oldInstances, oldAppConfig.instance);

					// if the current dependency is of a factory which is not anymore in the
					// available configurations then removed and added with a unique appIdKey with
					// the factory id for removing it at the end
					oldInstances.remove(new AppIdKey(a.getAppId(), d.key));
					oldInstances.put(new AppIdKey(a.getAppId(), d.key, oldAppConfig.instance.appId), oldAppConfig);
					oldAppConfig = null;

					// TODO maybe be able to reference this instance if it should be created in
					// another dependency
				}
			}

			var possibleInstance = this.findNeededApp(d, this.determineDependencyConfig(d.appConfigs));

			if (oldAppConfig == null //
					&& (d.createPolicy == DependencyDeclaration.CreatePolicy.ALWAYS //
							|| (d.createPolicy == DependencyDeclaration.CreatePolicy.IF_NOT_EXISTING
									&& possibleInstance.isEmpty()))) {
				var config = this.determineDependencyConfig(d.appConfigs);
				String appId;
				UUID id = null;
				List<Dependency> dependencies = null;
				if (config.appId != null) {
					appId = config.appId;
					id = UUID.randomUUID();
				} else {
					var instance = this.appManagerUtil.getInstanceById(config.specificInstanceId);
					appId = instance.appId;
					id = instance.instanceId;
					dependencies = instance.dependencies;
				}
				try {
					// check if an instance can be created
					this.appManagerUtil.getAppConfiguration(ConfigurationTarget.ADD, config.appId, config.alias,
							config.initialProperties, language);
					var instance = new OpenemsAppInstance(appId, config.alias, id, config.initialProperties,
							dependencies);
					this.temporaryApps.currentlyCreatingApps().add(instance);
					toCreateInstances.add(instance);
					return IncludeApp.INCLUDE_WITH_DEPENDENCIES;
				} catch (NoSuchElementException | OpenemsNamedException ex) {
					// app not found or config cant be get
					return IncludeApp.NOT_INCLUDED;
				}
			}
			// do not include apps twice
			if (possibleInstance != null && possibleInstance.isPresent()) {
				if (this.temporaryApps.currentlyCreatingApps().stream()
						.anyMatch(t -> t.equals(possibleInstance.get()))) {
					return IncludeApp.NOT_INCLUDED;
				}
			}

			if (oldAppConfig == null) {
				return IncludeApp.INCLUDE_ONLY_APP;
			}

			// remove dependencies which got not included because the app already exists
			removeAppsWithParent(oldInstances, oldAppConfig.instance);

			// do not include the dependencies if the app already exists
			return IncludeApp.INCLUDE_ONLY_APP;
		};
	}

	private static void removeAppsWithParent(//
			final Map<AppIdKey, ExistingDependencyConfig> instances, //
			final OpenemsAppInstance parent //
	) {
		instances.entrySet().stream() //
				.filter(t -> Objects.equals(t.getKey().parent, parent)) //
				.map(t -> t.getKey()) //
				.collect(Collectors.toList()) //
				.forEach(t -> instances.remove(t));
	}

	/**
	 * Removes properties which should not get saved e. g. passwords.
	 * 
	 * @param instance The {@link OpenemsAppInstance} to remove the properties from
	 */
	private void removeNotAllowedToSavedProperties(OpenemsAppInstance instance) {
		try {
			final var app = this.appManagerUtil.getAppById(instance.appId);
			Arrays.stream(app.getProperties()) //
					.filter(t -> !t.isAllowedToSave) //
					.forEach(t -> instance.properties.remove(t.name));
		} catch (NoSuchElementException e) {
			// app not found
		} catch (UnsupportedOperationException e) {
			// getting properties not supported
		}
	}

	private DependencyDeclaration.AppDependencyConfig getAppDependencyConfig(OpenemsAppInstance instance,
			List<DependencyDeclaration.AppDependencyConfig> appDependencyConfigs) {
		for (var config : appDependencyConfigs) {
			if (config.appId != null && config.appId.equals(instance.appId)
					|| config.specificInstanceId.equals(instance.instanceId)) {
				return config;
			}
		}
		return null;
	}

	private final DependencyDeclaration getNeededDependencyTo(OpenemsAppInstance instance, String appId,
			UUID instanceId) {
		try {
			var neededDependencies = this.appManagerUtil.getAppConfiguration(ConfigurationTarget.UPDATE, instance,
					null).dependencies;
			if (neededDependencies == null || neededDependencies.isEmpty()) {
				return null;
			}
			for (var neededDependency : neededDependencies) {
				// remove already satisfied dependencies
				if (instance.dependencies != null
						&& instance.dependencies.stream().anyMatch(d -> d.key.equals(neededDependency.key))) {
					continue;
				}

				if (neededDependency.appConfigs.stream().filter(c -> c.appId != null)
						.anyMatch(c -> c.appId.equals(appId))
						|| neededDependency.appConfigs.stream().filter(c -> c.specificInstanceId != null)
								.anyMatch(c -> c.specificInstanceId.equals(instanceId))) {
					return neededDependency;
				}

			}
		} catch (OpenemsNamedException e) {
			// can not get app configuration
			e.printStackTrace();
		}
		return null;
	}

	private static class AppIdKey implements Comparable<AppIdKey> {

		public final OpenemsAppInstance parent;

		public final String appId;
		public final String key;

		public final String actualAppId;

		public AppIdKey(OpenemsAppInstance parent, String appId, String key, String actualAppId) {
			this.parent = parent;
			this.appId = appId;
			this.key = key;
			this.actualAppId = actualAppId;
		}

		public AppIdKey(OpenemsAppInstance parent, String appId, String key) {
			this(parent, appId, key, null);
		}

		public AppIdKey(String appId, String key, String actualAppId) {
			this(null, appId, key, actualAppId);
		}

		public AppIdKey(String appId, String key) {
			this(null, appId, key, null);
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof AppIdKey)) {
				return false;
			}

			return ((AppIdKey) other).compareTo(this) == 0;
		}

		@Override
		public int compareTo(AppIdKey o) {
			return this.toString().compareTo(o.toString());
		}

		@Override
		public String toString() {
			return this.appId + ":" + this.key //
					+ (this.actualAppId != null ? ":" + this.actualAppId : "");
		}
	}

	private UpdateValues deleteAppInternal(User user, OpenemsAppInstance instance) throws OpenemsNamedException {

		final var language = user == null ? null : user.getLanguage();
		final var bundle = getTranslationBundle(language);

		BiFunction<OpenemsAppInstance, OpenemsAppInstance, Boolean> includeInstance = (p, i) -> {
			if (p != null) {
				// check if the parent should delete it
				try {
					var config = this.appManagerUtil.getAppConfiguration(ConfigurationTarget.DELETE, p, null);
					var dependency = p.dependencies.stream().filter(de -> de.instanceId.equals(i.instanceId))
							.findFirst();

					if (dependency.isEmpty()) {
						return false;
					}

					var dependencyDeclaration = config.dependencies.stream()
							.filter(dd -> dd.key.equals(dependency.get().key)).findFirst();

					if (dependencyDeclaration.isEmpty()) {
						return false;
					}

					switch (dependencyDeclaration.get().deletePolicy) {
					case NEVER:
						return false;
					case IF_MINE:
						var referencedApps = this.getAppsWithReferenceTo(i.instanceId);
						referencedApps.removeAll(this.temporaryApps.currentlyDeletingApps());
						for (var referencedInstance : referencedApps) {
							if (!referencedInstance.equals(p)) {
								return false;
							}
						}
						break;
					case ALWAYS:
						break;
					}

				} catch (OpenemsNamedException | NoSuchElementException e) {
					// don't include instance if broken
					return false;
				}
			}
			this.temporaryApps.currentlyDeletingApps().add(i);
			return true;
		};

		var warnings = new LinkedList<String>();

		this.foreachExistingDependency(instance, ConfigurationTarget.DELETE, language, includeInstance, dc -> {
			// check if dependency is allowed to be deleted by its parent
			if (dc.isDependency()) {
				var deleteApp = true;
				switch (dc.sub.deletePolicy) {
				case IF_MINE:
					if (!this.getAppManagerImpl().getInstantiatedApps().stream()
							.anyMatch(a -> !a.equals(dc.parentInstance) && a.dependencies != null && a.dependencies
									.stream().anyMatch(d -> d.instanceId.equals(dc.instance.instanceId)))) {
						break;
					}
					deleteApp = false;
					break;
				case NEVER:
					deleteApp = false;
					break;
				case ALWAYS:
					break;
				}
				if (!deleteApp) {
					// update for enabling ReadOnly after deleting ReadWrite
					if (dc.sub.updatePolicy != DependencyDeclaration.UpdatePolicy.ALWAYS) {
						return false;
					}

					var copy = dc.instance.properties.deepCopy();
					// override properties
					for (var entry : dc.appDependencyConfig.properties.entrySet()) {
						copy.add(entry.getKey(), entry.getValue());
					}

					try {
						var updateInstance = new OpenemsAppInstance(dc.instance.appId, //
								Optional.ofNullable(dc.appDependencyConfig.alias).orElse(dc.instance.alias), //
								dc.instance.instanceId, copy, null);
						this.updateAppInternal(user, dc.instance, updateInstance, dc.app);

					} catch (OpenemsNamedException e) {
						// can not update app
						warnings.add(e.getMessage());
						e.printStackTrace();
					}
					return false;
				}
			}

			this.temporaryApps.currentlyDeletingApps().add(dc.instance);

			this.aggregateAllTasks(null, dc.config);

			return true;
		});

		this.updateReferencesToRemovedInstances();

		// check if the app is allowed to be deleted
		if (!this.isAllowedToDelete(instance,
				this.temporaryApps.currentlyDeletingApps().stream().map(t -> t.instanceId).toArray(UUID[]::new))) {
			throw new OpenemsException(TranslationUtil.getTranslation(bundle, "appNotAllowedToBeDeleted"));
		}

		return new UpdateValues(instance, this.temporaryApps.currentlyCreatingModifiedApps(),
				this.temporaryApps.currentlyDeletingApps(), warnings);
	}

	private void updateReferencesToRemovedInstances() {
		var unmodifiedApps = this
				.getAppsWithReferenceTo(
						this.temporaryApps.currentlyDeletingApps().stream().map(t -> t.instanceId).toArray(UUID[]::new))
				.stream().filter(a -> !this.temporaryApps.currentlyDeletingApps().stream().anyMatch(t -> t.equals(a)))
				.collect(Collectors.toList());

		for (var app : unmodifiedApps) {
			var dependencies = new ArrayList<>(app.dependencies);
			dependencies.removeIf(d -> this.temporaryApps.currentlyDeletingApps().stream()
					.anyMatch(i -> i.instanceId.equals(d.instanceId)));
			this.temporaryApps.currentlyModifiedApps().removeIf(t -> t.instanceId.equals(app.instanceId));
			this.temporaryApps.currentlyModifiedApps().add(new OpenemsAppInstance(app.appId, //
					app.alias, app.instanceId, app.properties, dependencies));
		}
	}

	private List<OpenemsAppInstance> getAppsWithReferenceTo(UUID... instanceIds) {
		return this.getAppsWithReferenceTo(this.getAllInstances(), instanceIds);
	}

	private List<OpenemsAppInstance> getAppsWithReferenceTo(List<OpenemsAppInstance> instances, UUID... instanceIds) {
		return instances.stream() //
				.filter(i -> i.dependencies != null && !i.dependencies.isEmpty()) //
				.filter(i -> i.dependencies.stream().anyMatch(//
						d -> Arrays.stream(instanceIds).anyMatch(id -> id.equals(d.instanceId)))) //
				.collect(Collectors.toList());
	}

	private final void aggregateAllTasks(AppConfiguration instance, AppConfiguration oldInstance) {
		for (var task : this.tasks) {
			task.aggregate(instance, oldInstance);
		}
	}

	private void resetTasks() {
		for (var task : this.tasks) {
			task.reset();
		}
	}

	/**
	 * Checks if the instance is allowed to be deleted depending on other apps
	 * dependencies to this instance.
	 *
	 * @param instance  the app to delete
	 * @param ignoreIds the instance id's that should be ignored
	 * @return true if it is allowed to delete the app
	 */
	private final boolean isAllowedToDelete(OpenemsAppInstance instance, UUID... ignoreIds) {
		// check if a parent does not allow deletion of this instance
		for (var entry : this.getAppManagerImpl().appConfigs(
				this.getAppsWithReferenceTo(this.getAppManagerImpl().getInstantiatedApps(), instance.instanceId),
				AppManagerImpl.excludingInstanceIds(ignoreIds))) {
			for (var dependency : entry.getKey().dependencies) {
				if (!dependency.instanceId.equals(instance.instanceId)) {
					continue;
				}
				var declaration = entry.getValue().dependencies.stream().filter(dd -> dd.key.equals(dependency.key))
						.findAny();

				// declaration not found for dependency
				if (declaration.isEmpty()) {
					continue;
				}

				switch (declaration.get().dependencyDeletePolicy) {
				case ALLOWED:
					break;
				case NOT_ALLOWED:
					return false;
				}
			}
		}
		return true;
	}

	protected void checkStatus(OpenemsApp openemsApp, Language language) throws OpenemsNamedException {
		var validatorConfig = openemsApp.getValidatorConfig();
		var status = this.validator.getStatus(validatorConfig);
		switch (status) {
		case INCOMPATIBLE:
			throw new OpenemsException("App is not compatible! " + this.validator
					.getErrorCompatibleMessages(validatorConfig, language).stream().collect(Collectors.joining(";")));
		case COMPATIBLE:
			throw new OpenemsException("App can not be installed! " + this.validator
					.getErrorInstallableMessages(validatorConfig, language).stream().collect(Collectors.joining(";")));
		case INSTALLABLE:
			// app can be installed
			return;
		}
		throw new OpenemsException("Status '" + status.name() + "' is not implemented.");
	}

	protected static List<EdgeConfig.Component> getComponentsFromConfigs(List<AppConfiguration> configs) {
		return mapAppConfiguration(configs, c -> c.components);
	}

	protected static List<String> getSchedulerIdsFromConfigs(List<AppConfiguration> configs) {
		return mapAppConfiguration(configs, c -> c.schedulerExecutionOrder);
	}

	protected static List<InterfaceConfiguration> getStaticIpsFromConfigs(List<AppConfiguration> configs) {
		return mapAppConfiguration(configs, c -> c.ips);
	}

	private static <T> List<T> mapAppConfiguration(List<AppConfiguration> configs,
			Function<AppConfiguration, List<T>> mapper) {
		return configs.stream() //
				.map(mapper) //
				.flatMap(l -> l.stream()) //
				.collect(Collectors.toList());
	}

	/**
	 * Finds the needed app for a {@link DependencyDeclaration}.
	 *
	 * @param declaration the current {@link DependencyConfig}
	 * @param config      the current
	 *                    {@link DependencyDeclaration.AppDependencyConfig}
	 * @return null if the app can not be added; {@link Optional#empty()} if the app
	 *         needs to be created; the {@link OpenemsAppInstance} if an existing
	 *         app can be used
	 */
	private Optional<OpenemsAppInstance> findNeededApp(DependencyDeclaration declaration,
			DependencyDeclaration.AppDependencyConfig config) {
		if (declaration == null) {
			return Optional.empty();
		}
		if (config.specificInstanceId != null) {
			var appById = this.getInstance(config.specificInstanceId);
			return Optional.ofNullable(appById);
		}
		var appId = config.appId;
		if (declaration.createPolicy == DependencyDeclaration.CreatePolicy.ALWAYS) {
			var neededApps = this.getAllInstances().stream().filter(t -> t.appId.equals(appId))
					.collect(Collectors.toList());
			OpenemsAppInstance availableApp = null;
			for (var neededApp : neededApps) {
				if (this.appManagerUtil.getAppsWithDependencyTo(neededApp).isEmpty()) {
					availableApp = neededApp;
					break;
				}
			}
			return Optional.ofNullable(availableApp);
		}
		var neededApp = this.getAllInstances().stream().filter(t -> t.appId.equals(appId)).collect(Collectors.toList());
		if (!neededApp.isEmpty()) {
			return Optional.of(neededApp.get(0));
		}
		if (declaration.createPolicy == DependencyDeclaration.CreatePolicy.IF_NOT_EXISTING) {
			return Optional.empty();
		}
		return null;
	}

	private static enum IncludeApp {
		NOT_INCLUDED, //
		INCLUDE_ONLY_APP, //
		INCLUDE_WITH_DEPENDENCIES, //
		;
	}

	/**
	 * Recursively iterates over all dependencies and the given app.
	 *
	 * <p>
	 * Order bottom -> top.
	 *
	 * @param errors                    the errors that occur during the call
	 * @param app                       the app to be installed
	 * @param appConfig                 the {@link AppDependencyConfig} of the
	 *                                  current app
	 * @param target                    the {@link ConfigurationTarget}
	 * @param addConfig                 returns true if the instance gets created or
	 *                                  already exists
	 * @param sub                       the {@link DependencyDeclaration}
	 * @param l                         the {@link Language}
	 * @param parent                    the parent app
	 * @param alreadyIteratedInstances  the instances that already got iterated thru
	 *                                  to avoid endless loop. e. g. if two apps
	 *                                  have each other as a dependency
	 * @param determineDependencyConfig the function to determine the
	 *                                  {@link AppDependencyConfig}
	 * @param includeDependency         a {@link BiFunction} to determine if a
	 *                                  dependency should get included
	 * @param includeResult             the includeResult of the last iteration to
	 *                                  know if only the app without its
	 *                                  dependencies should be included
	 * @return the last {@link DependencyConfig}
	 * @throws OpenemsNamedException on error
	 */
	private DependencyConfig foreachDependency(List<String> errors, OpenemsApp app, AppDependencyConfig appConfig,
			ConfigurationTarget target, Function<DependencyConfig, Boolean> addConfig, DependencyDeclaration sub,
			Language l, OpenemsApp parent, Set<UUID> alreadyIteratedInstances,
			Function<List<AppDependencyConfig>, AppDependencyConfig> determineDependencyConfig,
			BiFunction<OpenemsApp, DependencyDeclaration, IncludeApp> includeDependency, IncludeApp includeResult)
			throws OpenemsNamedException {
		if (alreadyIteratedInstances == null) {
			alreadyIteratedInstances = new HashSet<>();
		}
		AppConfiguration config = null;
		try {
			config = this.appManagerUtil.getAppConfiguration(target, app, appConfig.alias, appConfig.initialProperties,
					l);
		} catch (OpenemsNamedException e) {
			// can not get config of app
			this.log.error(e.getMessage());
			errors.add(TranslationUtil.getTranslation(getTranslationBundle(l), "canNotGetAppConfigurationOfApp",
					app.getName(l)));
		}
		if (config == null) {
			return null;
		}
		var dependencies = new LinkedList<DependencyConfig>();
		if (includeResult == IncludeApp.INCLUDE_WITH_DEPENDENCIES) {

			for (var dependency : config.dependencies) {
				var nextAppConfig = determineDependencyConfig.apply(dependency.appConfigs);
				if (nextAppConfig == null) {
					// can not determine one out of many configs
					continue;
				}
				try {
					OpenemsApp dependencyApp;
					if (nextAppConfig.appId != null) {
						dependencyApp = this.appManagerUtil.getAppById(nextAppConfig.appId);
					} else {
						if (alreadyIteratedInstances.contains(nextAppConfig.specificInstanceId)) {
							continue;
						}
						alreadyIteratedInstances.add(nextAppConfig.specificInstanceId);
						var specificApp = this.getInstance(nextAppConfig.specificInstanceId);
						dependencyApp = this.appManagerUtil.getAppById(specificApp.appId);
						// fill up properties of existing app to make sure the appConfig can be get
						specificApp.properties.entrySet().forEach(entry -> {
							if (nextAppConfig.properties.has(entry.getKey())) {
								return;
							}
							nextAppConfig.properties.add(entry.getKey(), entry.getValue());
						});
					}

					var include = includeDependency.apply(app, dependency);
					if (include == IncludeApp.NOT_INCLUDED) {
						continue;
					}

					var addingConfig = this.foreachDependency(errors, dependencyApp, nextAppConfig, target, addConfig,
							dependency, l, app, alreadyIteratedInstances, determineDependencyConfig, includeDependency,
							include);
					if (addingConfig != null) {
						dependencies.add(addingConfig);
					}
				} catch (NoSuchElementException e) {
					// can not find app
					e.printStackTrace();
				}
			}

		}
		var newConfig = new DependencyConfig(app, parent, sub, config, appConfig, dependencies);
		if (addConfig.apply(newConfig)) {
			return newConfig;
		}
		return null;
	}

	private void foreachDependency(List<String> errors, OpenemsApp app, String alias, JsonObject defaultProperties,
			ConfigurationTarget target, Language l,
			Function<List<AppDependencyConfig>, AppDependencyConfig> determineDependencyConfig,
			BiFunction<OpenemsApp, DependencyDeclaration, IncludeApp> includeDependency,
			Function<DependencyConfig, Boolean> consumer) throws OpenemsNamedException {
		var appConfig = DependencyDeclaration.AppDependencyConfig.create() //
				.setAppId(app.getAppId()) //
				.setAlias(alias) //
				.setProperties(defaultProperties) //
				.build();

		this.foreachDependency(errors, app, appConfig, target, consumer, null, l, null, null, determineDependencyConfig,
				includeDependency, IncludeApp.INCLUDE_WITH_DEPENDENCIES);
	}

	private OpenemsAppInstance getInstance(UUID id) {
		if (this.temporaryApps != null) {
			var instance = this.temporaryApps.currentlyCreatingModifiedApps().stream()
					.filter(t -> t.instanceId.equals(id)).findAny();
			if (instance.isPresent()) {
				return instance.get();
			}
		}
		try {
			return this.appManagerUtil.getInstanceById(id);
		} catch (NoSuchElementException e) {
			return null;
		}
	}

	/**
	 * Gets an unmodifiable list of the existing instances an the instances that are
	 * currently installing.
	 *
	 * @return the {@link OpenemsAppInstance}s
	 */
	private List<OpenemsAppInstance> getAllInstances() {
		var instances = new ArrayList<OpenemsAppInstance>(this.getAppManagerImpl().getInstantiatedApps());
		instances.removeAll(this.temporaryApps.currentlyDeletingApps());
		instances.removeAll(this.temporaryApps.currentlyModifiedApps());
		instances.addAll(this.temporaryApps.currentlyModifiedApps());
		instances.addAll(this.temporaryApps.currentlyCreatingApps());
		return Collections.unmodifiableList(instances);
	}

	private DependencyDeclaration.AppDependencyConfig determineDependencyConfig(List<AppDependencyConfig> configs) {
		if (configs == null || configs.isEmpty()) {
			return null;
		}
		if (configs.size() == 1) {
			return configs.get(0);
		}

		for (var config : configs) {
			var instances = this.getAppManagerImpl().getInstantiatedApps().stream()
					.filter(i -> i.appId.equals(config.appId)).collect(Collectors.toList());
			for (var instance : instances) {
				var existingDependencies = this.appManagerUtil.getAppsWithDependencyTo(instance);
				if (existingDependencies.isEmpty()) {
					return config;
				}
			}
		}

		return configs.get(0);
	}

	private void foreachExistingDependency(OpenemsAppInstance instance, ConfigurationTarget target, Language l,
			BiFunction<OpenemsAppInstance, OpenemsAppInstance, Boolean> includeInstance,
			Function<ExistingDependencyConfig, Boolean> consumer) throws OpenemsNamedException {
		this.foreachExistingDependency(instance, target, consumer, null, null, l, null, includeInstance);
	}

	/**
	 * Recursively iterates over all existing dependencies and the given app.
	 *
	 * <p>
	 * Order bottom -> top.
	 *
	 * @param instance            the existing {@link OpenemsAppInstance}
	 * @param target              the {@link ConfigurationTarget}
	 * @param consumer            the consumer that gets executed for every instance
	 * @param parent              the parent instance of the current dependency
	 * @param sub                 the {@link DependencyDeclaration}
	 * @param l                   the {@link Language}
	 * @param alreadyIteratedApps the already iterated app to avoid an endless loop
	 * @param includeInstance     parent, instance, if the instance should get
	 *                            included
	 * @return the last {@link DependencyConfig}
	 * @throws OpenemsNamedException on error
	 */
	private DependencyConfig foreachExistingDependency(OpenemsAppInstance instance, ConfigurationTarget target,
			Function<ExistingDependencyConfig, Boolean> consumer, OpenemsAppInstance parent, DependencyDeclaration sub,
			Language l, Set<OpenemsAppInstance> alreadyIteratedApps,
			BiFunction<OpenemsAppInstance, OpenemsAppInstance, Boolean> includeInstance) throws OpenemsNamedException {
		if (alreadyIteratedApps == null) {
			alreadyIteratedApps = new HashSet<>();
		}
		alreadyIteratedApps.add(instance);
		var app = this.appManagerUtil.getAppById(instance.appId);
		var config = this.appManagerUtil.getAppConfiguration(target, app, instance, l);

		var dependecies = new ArrayList<DependencyConfig>();
		if (includeInstance == null || includeInstance.apply(parent, instance)) {

			if (instance.dependencies != null) {
				dependecies = new ArrayList<>(instance.dependencies.size());
				for (var dependency : instance.dependencies) {
					try {
						var dependencyApp = this.appManagerUtil.getInstanceById(dependency.instanceId);
						if (alreadyIteratedApps.contains(dependencyApp)) {
							continue;
						}
						var subApp = config.dependencies.stream().filter(t -> t.key.equals(dependency.key)).findFirst()
								.get();
						var dependencyConfig = this.foreachExistingDependency(dependencyApp, target, consumer, instance,
								subApp, l, alreadyIteratedApps, includeInstance);
						if (dependencyConfig != null) {
							dependecies.add(dependencyConfig);
						}
					} catch (NoSuchElementException e) {
						// can not find app
					}
				}
			}
		}
		OpenemsApp parentApp = null;
		if (parent != null) {
			parentApp = this.appManagerUtil.getAppById(parent.appId);
		}

		DependencyDeclaration.AppDependencyConfig dependencyAppConfig;
		if (sub == null) {
			dependencyAppConfig = DependencyDeclaration.AppDependencyConfig.create() //
					.setAppId(instance.appId) //
					.setProperties(instance.properties) //
					.setAlias(instance.alias) //
					.build();
		} else {
			dependencyAppConfig = this.getAppDependencyConfig(instance, sub.appConfigs);
		}

		var newConfig = new ExistingDependencyConfig(app, parentApp, sub, config, dependencyAppConfig, dependecies,
				parent, instance);
		if (consumer.apply(newConfig)) {
			return newConfig;
		}
		return null;
	}

	/**
	 * Gets the component id s that can be replaced.
	 *
	 * @param app        the components of which app
	 * @param properties the default properties to create an app instance of this
	 *                   app
	 * @return a list of {@link ReplacableIds}
	 * @throws OpenemsNamedException on error
	 */
	private final List<ReplacableIds> getReplaceableComponentIds(OpenemsApp app, JsonObject properties)
			throws OpenemsNamedException {
		final var prefix = "?_?_";
		var config = app.getAppConfiguration(ConfigurationTarget.TEST, properties, null);
		var copy = properties.deepCopy();

		Map<String, String> defaultIdToCurrentId = new HashMap<>();
		// remove already set ids
		for (var component : config.components) {
			String removeKey = null;
			for (var entry : copy.entrySet()) {
				var id = JsonUtils.getAsOptionalString(entry.getValue()).orElse(null);
				if (id != null && component.getId().startsWith(id)) {
					removeKey = entry.getKey();
					final var myId = id;
					final var defaultId = component.getId() //
							.substring(component.getId().indexOf(':') + 1);
					defaultIdToCurrentId.put(defaultId, myId);
					break;
				}
			}
			if (removeKey != null) {
				copy.remove(removeKey);
			}
		}

		config = app.getAppConfiguration(ConfigurationTarget.TEST, copy, null);

		for (var comp : config.components) {
			copy.addProperty(comp.getId(), prefix);
		}
		var configWithNewIds = app.getAppConfiguration(ConfigurationTarget.TEST, copy, null);
		Map<String, String> replaceableComponentIds = new HashMap<>();
		for (var comp : configWithNewIds.components) {
			if (comp.getId().startsWith(prefix)) {
				// "METER_ID:meter0"
				var raw = comp.getId().substring(prefix.length());
				// ["METER_ID", "meter0"]
				var pieces = raw.split(":");
				// "METER_ID"
				var property = pieces[0];
				// "meter0"
				var defaultId = pieces[1];
				replaceableComponentIds.put(property, defaultId);
			}
		}

		return replaceableComponentIds.entrySet().stream() //
				.map(entry -> new ReplacableIds(defaultIdToCurrentId.get(entry.getValue()), //
						entry.getValue(), entry.getKey())) //
				.collect(Collectors.toList()); //
	}

	private final class ReplacableIds {
		public final String predefinedId;
		public final String defaultId;
		public final String key;

		public ReplacableIds(String predefinedId, String defaultId, String key) {
			super();
			this.predefinedId = predefinedId == null ? defaultId : predefinedId;
			this.defaultId = defaultId;
			this.key = key;
		}
	}

	/**
	 * Gets an App Configuration with component id s, which can be used to create or
	 * rewrite the settings of the component.
	 *
	 * @param app                the {@link OpenemsApp}
	 * @param oldAppInstance     the old {@link OpenemsAppInstance}
	 * @param newAppInstance     the new {@link OpenemsAppInstance}
	 * @param otherAppComponents the components that are used from the other
	 *                           {@link OpenemsAppInstance}
	 * @param language           the language of the new config
	 * @return the AppConfiguration with the replaced ID s of the components
	 * @throws OpenemsNamedException on error
	 */
	private AppConfiguration getNewAppConfigWithReplacedIds(OpenemsApp app, OpenemsAppInstance oldAppInstance,
			OpenemsAppInstance newAppInstance, List<EdgeConfig.Component> otherAppComponents, Language language)
			throws OpenemsNamedException {

		var target = oldAppInstance == null ? ConfigurationTarget.ADD : ConfigurationTarget.UPDATE;

		final var replacableIds = this.getReplaceableComponentIds(app, newAppInstance.properties);
		final var propertiesCopy = newAppInstance.properties.deepCopy();

		var indexToId = new HashMap<String, ReplacableIds>();
		var index = 0;
		for (var id : replacableIds) {
			propertiesCopy.addProperty(id.key, String.valueOf(index));
			indexToId.put(String.valueOf(index), id);
			index++;
		}

		var newAppConfig = this.appManagerUtil.getAppConfiguration(target, app, newAppInstance.alias, propertiesCopy,
				language);

		final var orderedComponents = ComponentUtilImpl.order(newAppConfig.components);
		final var iterator = new ArrayList<>(orderedComponents).iterator();
		for (int i = 0; iterator.hasNext(); i++) {
			final var comp = iterator.next();

			var isNewComponent = true;
			final var replacableId = indexToId.get(comp.getId());
			final var canBeReplaced = replacableId != null;
			final var originalId = canBeReplaced ? replacableId.predefinedId : comp.getId();
			var id = originalId;
			EdgeConfig.Component foundComponent = null;

			// try to find a component with the necessary settings
			// has to be at first place to make sure no unnecessary components are created
			if (canBeReplaced) {
				// TODO include currently creating components
				foundComponent = this.componentUtil.getComponentByConfig(comp);
				if (foundComponent != null) {
					id = foundComponent.getId();
				}
			}

			// use component based on the last configuration
			if (foundComponent == null && oldAppInstance != null && canBeReplaced
					&& oldAppInstance.properties.has(replacableId.key)) {
				id = oldAppInstance.properties.get(replacableId.key).getAsString();
				foundComponent = this.componentManager.getEdgeConfig().getComponent(id).orElse(null);
				final var tempId = id;
				// other app uses the same component because they had the same configuration
				// now this app needs the component with a different configuration so now create
				// a new component
				if (foundComponent != null && (!foundComponent.getFactoryId().equals(comp.getFactoryId())
						|| otherAppComponents.stream().anyMatch(t -> t.getId().equals(tempId)))) {
					foundComponent = null;
				}
			}

			isNewComponent = isNewComponent && foundComponent == null;
			if (isNewComponent) {
				// if the id is not already set and there is no component with the default id
				// then use the default id
				foundComponent = this.componentManager.getEdgeConfig().getComponent(originalId).orElse(null);
				if (foundComponent == null) {
					// find component for currently creating apps
					for (var entry : this.getAppManagerImpl().appConfigs(
							this.temporaryApps.currentlyCreatingModifiedApps(),
							AppManagerImpl.excludingInstanceIds(newAppInstance.instanceId))) {
						foundComponent = entry.getValue().components.stream()
								.filter(t -> t.getId().equals(comp.getId())).findFirst().orElse(null);
						if (foundComponent != null) {
							break;
						}

					}
				}
				var sameIdInComponents = orderedComponents.subList(0, i).stream() //
						.map(c -> {
							var repId = indexToId.get(c.getId());
							return repId != null ? repId.defaultId : c.getId();
						}) //
						.anyMatch(t -> t.equals(originalId));

				var usedInPreviousConfig = false;
				final var alreadyUsedIds = new ArrayList<String>();
				if (canBeReplaced && oldAppInstance != null) {
					for (var entry : oldAppInstance.properties.entrySet()) {
						if (entry.getKey().equals(replacableId.key)) {
							continue;
						}
						var existingId = JsonUtils.getAsOptionalString(entry.getValue()).orElse(null);
						if (existingId == null) {
							continue;
						}
						alreadyUsedIds.add(existingId);
						if (existingId.equals(originalId)) {
							usedInPreviousConfig = true;
						}
					}
				}

				if (foundComponent == null && !sameIdInComponents && !usedInPreviousConfig) {
					id = originalId;
				} else {
					// replace number at the end and get the next available id
					id = canBeReplaced ? replacableId.defaultId : id;
					var baseName = id.replaceAll("\\d+", "");
					var startingNumberString = id.replace(baseName, "");
					if (startingNumberString.isBlank()) {
						// TODO maybe error
						// no number at the end of the component id
						if (baseName.startsWith("_")) {
							// core component like _power, _componentManager
						}
					} else {
						var startingNumber = Integer.parseInt(startingNumberString);
						var ids = new ArrayList<>(orderedComponents).stream() //
								.map(EdgeConfig.Component::getId) //
								.collect(Collectors.toList());
						// add id if it was in the old configuration
						ids.addAll(alreadyUsedIds);

						var nextAvailableId = this.componentUtil.getNextAvailableId(baseName, startingNumber, ids);
						if (!nextAvailableId.equals(id) && !canBeReplaced) {
							// component can not be created because the id is already used
							// and the id can not be set in the configuration
							continue;
						}
						if (canBeReplaced) {
							id = nextAvailableId;
						}
					}
				}
			}

			if (canBeReplaced) {
				// replace component with new id
				final var component = orderedComponents.remove(i);
				orderedComponents.add(i,
						new EdgeConfig.Component(id, component.getAlias(), component.getFactoryId(),
								component.getProperties().entrySet().stream()
										.collect(JsonUtils.toJsonObject(Entry::getKey, Entry::getValue))));
				newAppInstance.properties.addProperty(replacableId.key, id);
			}
		}
		return this.appManagerUtil.getAppConfiguration(target, newAppInstance, language);
	}

	private final AppManagerImpl getAppManagerImpl() {
		var appManagerImpl = this.appManager;
		if (appManagerImpl == null) {
			appManagerImpl = this.componentManager.getEnabledComponentsOfType(AppManager.class).get(0);
		}
		return (AppManagerImpl) appManagerImpl;
	}

	private static ResourceBundle getTranslationBundle(Language language) {
		if (language == null) {
			language = Language.DEFAULT;
		}
		// TODO translation
		switch (language) {
		case CZ:
		case ES:
		case FR:
		case NL:
			language = Language.EN;
			break;
		case DE:
		case EN:
			break;
		}

		return ResourceBundle.getBundle("io.openems.edge.core.appmanager.dependency.translation", language.getLocal());
	}

	@Override
	public TemporaryApps getTemporaryApps() {
		return Optional.ofNullable(this.temporaryApps) //
				.map(TemporaryApps::unmodifiableApps) //
				.orElse(null);
	}

}
