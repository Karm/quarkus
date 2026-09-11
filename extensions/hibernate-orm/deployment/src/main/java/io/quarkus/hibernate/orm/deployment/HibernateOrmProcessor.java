package io.quarkus.hibernate.orm.deployment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hibernate.annotations.processing.Find;
import org.hibernate.annotations.processing.HQL;
import org.hibernate.annotations.processing.SQL;
import org.hibernate.boot.beanvalidation.BeanValidationIntegrator;
import org.hibernate.query.sqm.mutation.internal.temptable.LocalTemporaryTableInsertStrategy;
import org.hibernate.query.sqm.mutation.internal.temptable.LocalTemporaryTableMutationStrategy;
import org.jboss.jandex.DotName;

import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.NativeImageFeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.quarkus.hibernate.orm.deployment.integration.HibernateOrmIntegrationRuntimeConfiguredBuildItem;
import io.quarkus.hibernate.orm.deployment.integration.HibernateOrmIntegrationStaticConfiguredBuildItem;
import io.quarkus.hibernate.orm.runtime.graal.RegisterServicesForReflectionFeature;
import io.quarkus.hibernate.orm.runtime.graal.RegisterStateManagementForReflectionFeature;

/**
 * Simulacrum of JPA bootstrap.
 * <p>
 * This does not address the proper integration with Hibernate
 * Rather prepare the path to providing the right metadata
 *
 * @author Emmanuel Bernard emmanuel@hibernate.org
 * @author Sanne Grinovero <sanne@hibernate.org>
 */
@BuildSteps(onlyIf = HibernateOrmEnabled.class)
public final class HibernateOrmProcessor {

    /**
     * Collection of Hibernate annotations for which, if detected on interface, Hibernate processor generates repository.
     */
    public static final Set<Class<?>> HIBERNATE_REPOSITORY_ANNOTATIONS = Set.of(Find.class, HQL.class, SQL.class);

    @BuildStep
    NativeImageFeatureBuildItem registerServicesForReflection(BuildProducer<ServiceProviderBuildItem> services) {
        for (DotName serviceProvider : ClassNames.SERVICE_PROVIDERS) {
            services.produce(ServiceProviderBuildItem.allProvidersFromClassPath(serviceProvider.toString()));
        }

        return new NativeImageFeatureBuildItem(RegisterServicesForReflectionFeature.class);
    }

    @BuildStep
    NativeImageFeatureBuildItem registerStateManagementForReflection() {
        return new NativeImageFeatureBuildItem(RegisterStateManagementForReflectionFeature.class);
    }

    @BuildStep
    void registerStrategyForReflection(
            BuildProducer<ReflectiveClassBuildItem> reflective) {

        // Hibernate ORM uses reflection at runtime two create these two strategies,
        // So we need this to support native-image
        // These strategies are only used in offline mode https://github.com/quarkusio/quarkus/pull/48130 so far
        // When Hibernate will support temporary table creation inside the `hbm2ddl` tool
        // https://hibernate.atlassian.net/browse/HHH-15525 the strategies won't be needed anymore and this can be removed
        reflective.produce(ReflectiveClassBuildItem.builder(
                LocalTemporaryTableInsertStrategy.class,
                LocalTemporaryTableMutationStrategy.class)
                .reason(ClassNames.HIBERNATE_ORM_PROCESSOR.toString())
                .methods().fields().build());
    }

    @BuildStep
    public void enrollBeanValidationTypeSafeActivatorForReflection(Capabilities capabilities,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        if (capabilities.isPresent(Capability.HIBERNATE_VALIDATOR)) {
            // BeanValidationIntegrator is only added if this capability is present, see FastBootMetadataBuilder

            // Accessed in org.hibernate.boot.beanvalidation.BeanValidationIntegrator.loadTypeSafeActivatorClass
            reflectiveClasses.produce(ReflectiveClassBuildItem.builder("org.hibernate.boot.beanvalidation.TypeSafeActivator")
                    .methods().fields().build());
            // Accessed in org.hibernate.boot.beanvalidation.BeanValidationIntegrator.isBeanValidationApiAvailable
            reflectiveClasses.produce(ReflectiveClassBuildItem.builder(BeanValidationIntegrator.JAKARTA_BV_CHECK_CLASS)
                    .constructors(false).build());
        }
    }

    /*
     * Enable reflection for methods annotated with @InjectService,
     * such as org.hibernate.engine.jdbc.cursor.internal.StandardRefCursorSupport.injectJdbcServices.
     */
    @BuildStep
    public void registerInjectServiceMethodsForReflection(CombinedIndexBuildItem index,
            BuildProducer<ReflectiveClassBuildItem> reflective) {
        Set<String> classes = new HashSet<>();

        // Built-in service classes; can't rely on Jandex as Hibernate ORM is not indexed by default.
        ClassNames.ANNOTATED_WITH_INJECT_SERVICE.stream()
                .map(DotName::toString)
                .forEach(classes::add);

        // Integrators relying on @InjectService.
        index.getIndex().getAnnotations(ClassNames.INJECT_SERVICE).stream()
                .map(a -> a.target().asMethod().declaringClass().name().toString())
                .forEach(classes::add);

        if (!classes.isEmpty()) {
            reflective.produce(ReflectiveClassBuildItem.builder(classes.toArray(new String[0]))
                    .reason(ClassNames.HIBERNATE_ORM_PROCESSOR.toString())
                    .constructors(false).methods().build());
        }
    }

    @SuppressWarnings("deprecation")
    @BuildStep
    void bridgeStaticIntegrationBuildItems(
            List<io.quarkus.hibernate.orm.deployment.spi.HibernateOrmIntegrationStaticConfiguredBuildItem> spiStaticItems,
            BuildProducer<HibernateOrmIntegrationStaticConfiguredBuildItem> staticProducer) {
        for (var spiItem : spiStaticItems) {
            HibernateOrmIntegrationStaticConfiguredBuildItem item = new HibernateOrmIntegrationStaticConfiguredBuildItem(
                    spiItem.getIntegrationName(), spiItem.getPersistenceUnitName());
            if (spiItem.getInitListener() != null) {
                item.setInitListener(spiItem.getInitListener());
            }
        }
    }

    void registerJakartaDataRepositorySecurityAnnotations(Capabilities capabilities,
            BuildProducer<SecuredInterfaceAnnotationBuildItem> securedInterfaceAnnotationProducer) {
        if (capabilities.isPresent(Capability.SECURITY)) {
            securedInterfaceAnnotationProducer.produce(ofClassAnnotation(JAKARTA_DATA_REPOSITORY_ANNOTATION));
            HIBERNATE_REPOSITORY_ANNOTATIONS
                    .forEach(annotation -> securedInterfaceAnnotationProducer.produce(ofMethodAnnotation(annotation)));
        }
    }

    /**
     * Hibernate ORM checks package-info and if we have a negative lookup, it's not cached by AOT class loading.
     * <p>
     * So point of this method is to generate an empty package-info in packages where we have a mapped class,
     * if there isn't a package-info already.
     */
    @BuildStep(onlyIf = AotJarEnabled.class, onlyIfNot = NativeOrNativeSourcesBuild.class)
    void generateMissingPackageInfos(CombinedIndexBuildItem combinedIndex,
            JpaModelBuildItem jpaModel,
            List<ApplicationClassPredicateBuildItem> predicates,
            BuildProducer<GeneratedClassBuildItem> generatedClasses,
            BuildProducer<GeneratedResourceBuildItem> generatedResources,
            BuildProducer<GeneratedServiceProviderBuildItem> generatedServiceProviders) {

        IndexView index = combinedIndex.getIndex();

        Set<String> packages = new HashSet<>();
        for (String entityClass : jpaModel.getManagedClassNames()) {
            int idx = entityClass.lastIndexOf('.');
            if (idx > 0) {
                packages.add(entityClass.substring(0, idx));
            }
        }

        if (packages.isEmpty()) {
            return;
        }

        Predicate<String> appClassPredicate = new Predicate<String>() {
            @Override
            public boolean test(String className) {
                for (ApplicationClassPredicateBuildItem predicate : predicates) {
                    if (predicate.test(className)) {
                        return true;
                    }
                }
                return GeneratedClassGizmo2Adaptor.isApplicationClass(className);
            }
        };

        Gizmo gizmo = Gizmo
                .create(new GeneratedClassGizmo2Adaptor(generatedClasses, generatedResources, generatedServiceProviders,
                        appClassPredicate))
                .withDebugInfo(false)
                .withParameters(false);

        for (String pkg : packages) {
            String packageInfoClassName = pkg + ".package-info";
            if (index.getClassByName(DotName.createSimple(packageInfoClassName)) != null) {
                // we already have a package-info, we don't generate an empty one
                continue;
            }

            gizmo.interface_(packageInfoClassName, cc -> {
                cc.synthetic();
            });
        }
    }

    @BuildStep
    public void buildBlockingPersistenceUnitsFromConfig(
            HibernateOrmConfig hibernateOrmConfig,
            List<PersistenceUnitDefinitionBuildItem> persistenceUnitDefinitions,
            JpaModelPerPersistenceUnitBuildItem jpaModel,
            List<JdbcDataSourceBuildItem> jdbcDataSources,
            ApplicationArchivesBuildItem applicationArchivesBuildItem,
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            List<SqlLoadScriptDefaultBuildItem> additionalSqlLoadScriptDefaults,
            BuildProducer<NativeImageResourceBuildItem> nativeImageResources,
            BuildProducer<HotDeploymentWatchedFileBuildItem> hotDeploymentWatchedFiles,
            BuildProducer<PersistenceUnitDescriptorBuildItem> persistenceUnitDescriptors,
            BuildProducer<ReflectiveMethodBuildItem> reflectiveMethods,
            List<DatabaseKindDialectBuildItem> dbKindMetadataBuildItems) {
        for (PersistenceUnitDefinitionBuildItem puDefinition : persistenceUnitDefinitions) {
            if (puDefinition.getParadigm() != ProgrammingParadigm.BLOCKING) {
                continue;
            }
            var model = jpaModel.getModelPerPersistenceUnit().get(puDefinition.getPersistenceUnitName());
            if (model == null) {
                model = new JpaPersistenceUnitModel();
            }
            buildBlockingPersistenceUnitFromConfig(
                    hibernateOrmConfig, puDefinition, model,
                    jdbcDataSources, applicationArchivesBuildItem, launchMode.getLaunchMode(), capabilities,
                    additionalSqlLoadScriptDefaults,
                    nativeImageResources, hotDeploymentWatchedFiles, persistenceUnitDescriptors,
                    reflectiveMethods, dbKindMetadataBuildItems);
        }
    }

    private static void buildBlockingPersistenceUnitFromConfig(
            HibernateOrmConfig hibernateOrmConfig,
            PersistenceUnitDefinitionBuildItem puDefinition,
            JpaPersistenceUnitModel model,
            List<JdbcDataSourceBuildItem> jdbcDataSources,
            ApplicationArchivesBuildItem applicationArchivesBuildItem,
            LaunchMode launchMode,
            Capabilities capabilities,
            List<SqlLoadScriptDefaultBuildItem> additionalSqlLoadScriptDefaults,
            BuildProducer<NativeImageResourceBuildItem> nativeImageResources,
            BuildProducer<HotDeploymentWatchedFileBuildItem> hotDeploymentWatchedFiles,
            BuildProducer<PersistenceUnitDescriptorBuildItem> persistenceUnitDescriptors,
            BuildProducer<ReflectiveMethodBuildItem> reflectiveMethods,
            List<DatabaseKindDialectBuildItem> dbKindMetadataBuildItems) {
        String persistenceUnitName = puDefinition.getPersistenceUnitName();
        HibernateOrmConfigPersistenceUnit persistenceUnitConfig = puDefinition.getConfig();
        Optional<PersistenceUnitDefinitionBuildItem.AdditionalConfig> additionalPuConfig = puDefinition.getAdditionalConfig();
        Optional<String> dataSourceName = puDefinition.getDataSourceName();
        Optional<JdbcDataSourceBuildItem> jdbcDataSource = dataSourceName
                .map(name -> HibernateProcessorUtil.findDataSourceWithName(name,
                        jdbcDataSources,
                        JdbcDataSourceBuildItem::getName));

        Properties descriptorProperties = new Properties();
        additionalPuConfig.ifPresent(c -> descriptorProperties.putAll(c.properties()));

        QuarkusPersistenceUnitDescriptor descriptor = new QuarkusPersistenceUnitDescriptor(
                persistenceUnitName,
                new HibernateOrmPersistenceUnitProviderHelper(),
                PersistenceUnitTransactionType.JTA,
                // That's right, we're pushing both class names and package names
                // to a method called "addClasses".
                // It's a misnomer: while the method populates the set that backs getManagedClasses(),
                // that method is also poorly named because it can actually return both class names
                // and package names.
                // See for proof:
                // - how org.hibernate.boot.archive.scan.internal.ScanResultCollector.isListedOrDetectable
                //   is used for packages too, even though it relies (indirectly) on getManagedClassNames().
                // - the comment at org/hibernate/boot/model/process/internal/ScanningCoordinator.java:246:
                //   "IMPL NOTE : "explicitlyListedClassNames" can contain class or package names..."
                new ArrayList<>(model.allModelClassAndPackageNames()),
                descriptorProperties,
                false);
        Set<String> entityClassNames = model.entityClassNames();

        MultiTenancyStrategy multiTenancyStrategy = HibernateProcessorUtil
                .getMultiTenancyStrategy(persistenceUnitConfig.multitenant());

        Optional<String> explicitDialect = additionalPuConfig
                .flatMap(PersistenceUnitDefinitionBuildItem.AdditionalConfig::explicitDialect)
                .or(() -> persistenceUnitConfig.dialect().dialect());
        Optional<DatabaseKind.SupportedDatabaseKind> supportedDatabaseKind = collectDialectConfig(persistenceUnitName,
                persistenceUnitConfig,
                dbKindMetadataBuildItems, jdbcDataSource, multiTenancyStrategy,
                explicitDialect,
                reflectiveMethods, descriptor.getProperties()::setProperty);

        configureProperties(descriptor, persistenceUnitConfig, hibernateOrmConfig, false);

        if (capabilities.isPresent(Capability.JACKSON)) {
            descriptor.getProperties().setProperty(MappingSettings.JSON_FORMAT_MAPPER,
                    JACKSON_3_JSON_FORMAT_MAPPER);
        }

        if (additionalPuConfig.isEmpty()) {
            configureSqlLoadScript(persistenceUnitName, persistenceUnitConfig, applicationArchivesBuildItem, launchMode,
                    additionalSqlLoadScriptDefaults,
                    nativeImageResources, hotDeploymentWatchedFiles, descriptor);
        }

        persistenceUnitDescriptors.produce(
                new PersistenceUnitDescriptorBuildItem(descriptor,
                        new RecordedConfig(
                                dataSourceName,
                                jdbcDataSource.map(JdbcDataSourceBuildItem::getDbKind),
                                supportedDatabaseKind.map(DatabaseKind.SupportedDatabaseKind::getMainName),
                                jdbcDataSource.flatMap(JdbcDataSourceBuildItem::getDbVersion),
                                jdbcDataSource.map(JdbcDataSourceBuildItem::isDbVersionUserSpecified).orElse(false),
                                explicitDialect,
                                entityClassNames,
                                multiTenancyStrategy,
                                hibernateOrmConfig.database().ormCompatibilityVersion(),
                                persistenceUnitConfig.unsupportedProperties()),
                        model.xmlMappings(),
                        false,
                        isHibernateValidatorPresent(capabilities)));
    }

    private static Optional<DatabaseKind.SupportedDatabaseKind> collectDialectConfig(String persistenceUnitName,
            HibernateOrmConfigPersistenceUnit persistenceUnitConfig,
            List<DatabaseKindDialectBuildItem> dbKindMetadataBuildItems,
            Optional<JdbcDataSourceBuildItem> jdbcDataSource,
            MultiTenancyStrategy multiTenancyStrategy,
            Optional<String> dialect,
            BuildProducer<ReflectiveMethodBuildItem> reflectiveMethods,
            BiConsumer<String, String> puPropertiesCollector) {
        final HibernateOrmConfigPersistenceUnit.HibernateOrmConfigPersistenceUnitDialect dialectConfig = persistenceUnitConfig
                .dialect();

        Optional<String> dbKind = jdbcDataSource.map(JdbcDataSourceBuildItem::getDbKind);
        Optional<String> dbVersion = jdbcDataSource.flatMap(JdbcDataSourceBuildItem::getDbVersion);
        if (multiTenancyStrategy != MultiTenancyStrategy.DATABASE && jdbcDataSource.isEmpty()) {
            String dsConfigProperty = HibernateOrmRuntimeConfig.puPropertyKey(persistenceUnitName, "datasource");
            throw new ConfigurationException(String.format(Locale.ROOT,
                    "Datasource must be defined for persistence unit '%s'. Setting the datasource for the persistence unit can be done via the '%s' property. "
                            + " Refer to https://quarkus.io/guides/datasource for guidance.",
                    persistenceUnitName, dsConfigProperty),
                    new HashSet<>(Arrays.asList("quarkus.datasource.db-kind", "quarkus.datasource.username",
                            "quarkus.datasource.password", "quarkus.datasource.jdbc.url")));
        }

        Optional<DatabaseKind.SupportedDatabaseKind> supportedDatabaseKind = setDialectAndStorageEngine(
                persistenceUnitName,
                dbKind,
                dialect,
                dbVersion,
                dialectConfig,
                dbKindMetadataBuildItems,
                puPropertiesCollector);

        if ((dbKind.isPresent() && DatabaseKind.isPostgreSQL(dbKind.get())
                || (dialect.isPresent() && dialect.get().toLowerCase(Locale.ROOT).contains("postgres")))) {
            // Workaround for https://hibernate.atlassian.net/browse/HHH-19063
            reflectiveMethods.produce(new ReflectiveMethodBuildItem(
                    "Accessed in org.hibernate.engine.jdbc.env.internal.DefaultSchemaNameResolver.determineAppropriateResolverDelegate",
                    "org.postgresql.jdbc.PgConnection", "getSchema"));
        }

        return supportedDatabaseKind;
    }

    private static void collectDialectConfigForPersistenceXml(String persistenceUnitName,
            PersistenceUnitDescriptor puDescriptor, List<DefaultDataSourceDbVersionBuildItem> defaultDbVersions) {
        Properties properties = puDescriptor.getProperties();
        String dialect = puDescriptor.getProperties().getProperty(AvailableSettings.DIALECT);
        // Legacy behavior: we used to do this through a custom DialectSelector,
        // but we might as well do it at build time.
        if (("H2".equals(dialect) || "org.hibernate.dialect.H2Dialect".equals(dialect))
                && !properties.containsKey(AvailableSettings.JAKARTA_HBM2DDL_DB_MAJOR_VERSION)
                && !properties.containsKey(AvailableSettings.JAKARTA_HBM2DDL_DB_MINOR_VERSION)
                && !properties.containsKey(AvailableSettings.JAKARTA_HBM2DDL_DB_VERSION)) {
            Optional<String> defaultH2Version = DefaultDataSourceDbVersionBuildItem.resolveDefaultDbVersion("h2",
                    defaultDbVersions);
            if (defaultH2Version.isPresent()) {
                Logger.getLogger(HibernateOrmProcessor.class)
                        .infof("Persistence unit '%1$s': Enforcing Quarkus defaults for dialect 'org.hibernate.dialect.H2Dialect'"
                                + " by automatically setting '%2$s=%3$s'.",
                                persistenceUnitName, AvailableSettings.JAKARTA_HBM2DDL_DB_VERSION, defaultH2Version.get());
                properties.setProperty(AvailableSettings.JAKARTA_HBM2DDL_DB_VERSION, defaultH2Version.get());
            }
            item.setXmlMappingRequired(spiItem.isXmlMappingRequired());
            staticProducer.produce(item);
        }
    }

    @SuppressWarnings("deprecation")
    @BuildStep
    void bridgeRuntimeIntegrationBuildItems(
            List<io.quarkus.hibernate.orm.deployment.spi.HibernateOrmIntegrationRuntimeConfiguredBuildItem> spiRuntimeItems,
            BuildProducer<HibernateOrmIntegrationRuntimeConfiguredBuildItem> runtimeProducer) {
        for (var spiItem : spiRuntimeItems) {
            HibernateOrmIntegrationRuntimeConfiguredBuildItem item = new HibernateOrmIntegrationRuntimeConfiguredBuildItem(
                    spiItem.getIntegrationName(), spiItem.getPersistenceUnitName());
            if (spiItem.getInitListener() != null) {
                item.setInitListener(spiItem.getInitListener());
            }
            runtimeProducer.produce(item);
        }
    }
}
