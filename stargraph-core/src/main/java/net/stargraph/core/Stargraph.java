package net.stargraph.core;

/*-
 * ==========================License-Start=============================
 * stargraph-core
 * --------------------------------------------------------------------
 * Copyright (C) 2017 Lambda^3
 * --------------------------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * ==========================License-End===============================
 */

import com.typesafe.config.*;
import net.stargraph.ModelUtils;
import net.stargraph.StarGraphException;
import net.stargraph.core.graph.GraphSearcher;
import net.stargraph.core.impl.corenlp.NERSearcher;
import net.stargraph.core.impl.elastic.ElasticEntitySearcher;
import net.stargraph.core.impl.hdt.HDTModelFactory;
import net.stargraph.core.impl.jena.JenaGraphSearcher;
import net.stargraph.core.index.Indexer;
import net.stargraph.core.ner.NER;
import net.stargraph.core.processors.Processors;
import net.stargraph.core.search.BaseSearcher;
import net.stargraph.core.search.EntitySearcher;
import net.stargraph.core.search.Searcher;
import net.stargraph.data.DataProvider;
import net.stargraph.data.DataProviderFactory;
import net.stargraph.data.processor.Holder;
import net.stargraph.data.processor.Processor;
import net.stargraph.data.processor.ProcessorChain;
import net.stargraph.model.BuiltInModel;
import net.stargraph.model.KBId;
import net.stargraph.query.Language;
import org.apache.jena.rdf.model.Model;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The Stargraph database core implementation.
 */
public final class Stargraph {

    private Logger logger = LoggerFactory.getLogger(getClass());
    private Marker marker = MarkerFactory.getMarker("core");
    private Config mainConfig;
    private String dataRootDir;
    private Map<String, KBLoader> kbLoaders;
    private Map<KBId, Indexer> indexers;
    private Map<KBId, Searcher> searchers;
    private Map<KBId, Directory> luceneDirs;
    private Map<String, Namespace> namespaces;
    private Map<String, NER> ners;
    private IndicesFactory indicesFactory;
    private GraphModelFactory modelFactory;
    private boolean initialized;

    public Stargraph() {
        this(ConfigFactory.load().getConfig("stargraph"), true);
    }

    public Stargraph(Config cfg, boolean initialize) {
        logger.info(marker, "Memory: {}", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage());

        if (System.getProperty("config.file") == null) {
            logger.warn(marker, "No configuration found at '-Dconfig.file'.");
        }

        this.mainConfig = Objects.requireNonNull(cfg);
        logger.trace(marker, "Configuration: {}", ModelUtils.toStr(mainConfig));
        this.indexers = new ConcurrentHashMap<>();
        this.searchers = new ConcurrentHashMap<>();
        this.luceneDirs = new ConcurrentHashMap<>();
        this.namespaces = new ConcurrentHashMap<>();
        this.kbLoaders = new ConcurrentHashMap<>();
        this.ners = new ConcurrentHashMap<>();

        setDataRootDir(mainConfig.getString("data.root-dir")); // absolute path is expected
        setIndicesFactory(createIndicesFactory());
        setModelFactory(new HDTModelFactory(this));

        if (initialize) {
            initialize();
        }
    }

    @SuppressWarnings("unchecked")
    public Class<Serializable> getModelClass(String modelName) {
        // This should change to support user's models.

        for (BuiltInModel entry : BuiltInModel.values()) {
            if (entry.modelId.equals(modelName)) {
                return entry.cls;
            }
        }

        throw new StarGraphException("No Class registered for model: '" + modelName + "'");
    }

    public Namespace getNamespace(String dbId) {
        return namespaces.computeIfAbsent(dbId, (id) -> Namespace.create(this, dbId));
    }

    public EntitySearcher createEntitySearcher() {
        return new ElasticEntitySearcher(this);
    }

    public GraphSearcher createGraphSearcher(String dbId) {
        return new JenaGraphSearcher(dbId, this);
    }

    public Model getGraphModel(String dbId) {
        return modelFactory.getModel(dbId);
    }

    public Config getConfig() {
        return mainConfig;
    }

    public Config getKBConfig(String dbId) {
        return mainConfig.getConfig(String.format("kb.%s", dbId));
    }

    public Config getKBConfig(KBId kbId) {
        return mainConfig.getConfig(kbId.getKBPath());
    }

    public Config getTypeConfig(KBId kbId) {
        return mainConfig.getConfig(kbId.getTypePath());
    }

    public KBLoader getKBLoader(String dbId) {
        return kbLoaders.computeIfAbsent(dbId, (id) -> new KBLoader(this, id));
    }

    public List<KBId> getKBIdsOf(String dbId) {
        return searchers.keySet().stream()
                .filter(kbId -> kbId.getId().equals(Objects.requireNonNull(dbId))).collect(Collectors.toList());
    }

    public Set<KBId> getKBs() {
        return indexers.keySet();
    }

    public boolean hasKB(String id) {
        return getKBs().stream().anyMatch(kbId -> kbId.getId().equals(id));
    }

    public Language getLanguage(String dbId) {
        Config kbCfg = getKBConfig(dbId);
        return Language.valueOf(kbCfg.getString("language").toUpperCase());
    }

    public NER getNER(String dbId) {
        //TODO: Should have a factory to ease test other implementation just changing configuration.
        return ners.computeIfAbsent(dbId, (id) -> new NERSearcher(getLanguage(id), createEntitySearcher(), id));
    }

    public String getDataRootDir() {
        return dataRootDir;
    }

    public Directory getLuceneDir(KBId kbId) {
        return luceneDirs.computeIfAbsent(kbId,
                (id) -> {
                    try {
                        return new MMapDirectory(Paths.get(getDataRootDir(), id.getId(), id.getType(), "idx"));
                    } catch (IOException e) {
                        throw new StarGraphException(e);
                    }
                });
    }

    public Indexer getIndexer(KBId kbId) {
        if (indexers.keySet().contains(kbId))
            return indexers.get(kbId);
        throw new StarGraphException("Indexer not found nor initialized: " + kbId);
    }

    public Searcher getSearcher(KBId kbId) {
        if (searchers.keySet().contains(kbId))
            return searchers.get(kbId);
        throw new StarGraphException("Searcher not found nor initialized: " + kbId);
    }

    public void setDataRootDir(String dataRootDir) {
        this.dataRootDir = Objects.requireNonNull(dataRootDir);
    }

    public void setDataRootDir(File dataRootDir) {
        this.dataRootDir = Objects.requireNonNull(dataRootDir.getAbsolutePath());
    }

    public void setIndicesFactory(IndicesFactory indicesFactory) {
        this.indicesFactory = Objects.requireNonNull(indicesFactory);
    }

    public void setModelFactory(GraphModelFactory modelFactory) {
        this.modelFactory = Objects.requireNonNull(modelFactory);
    }

    public ProcessorChain createProcessorChain(KBId kbId) {
        List<? extends Config> processorsCfg = getProcessorsCfg(kbId);
        if (processorsCfg != null && processorsCfg.size() != 0) {
            List<Processor> processors = new ArrayList<>();
            processorsCfg.forEach(config -> processors.add(Processors.create(this, config)));
            ProcessorChain chain = new ProcessorChain(processors);
            logger.info(marker, "processors = {}", chain);
            return chain;
        }
        logger.warn(marker, "No processors configured for {}", kbId);
        return null;
    }

    public DataProvider<? extends Holder> createDataProvider(KBId kbId) {
        DataProviderFactory factory;

        try {
            String className = getDataProviderCfg(kbId).getString("class");
            Class<?> providerClazz = Class.forName(className);
            Constructor[] constructors = providerClazz.getConstructors();

            if (BaseDataProviderFactory.class.isAssignableFrom(providerClazz)) {
                // It's our internal factory hence we inject the core dependency.
                factory = (DataProviderFactory) constructors[0].newInstance(this);
            } else {
                // This should be a user factory without constructor.
                // API user should rely on configuration or other means to initialize.
                // See TestDataProviderFactory as an example
                factory = (DataProviderFactory) providerClazz.newInstance();
            }

            DataProvider<? extends Holder> provider = factory.create(kbId);

            if (provider == null) {
                throw new IllegalStateException("DataProvider not created!");
            }

            logger.info(marker, "Creating {} data provider", kbId);
            return provider;
        } catch (Exception e) {
            throw new StarGraphException("Fail to initialize data provider: " + kbId, e);
        }
    }

    public synchronized final void initialize() {
        if (initialized) {
            throw new IllegalStateException("Core already initialized.");
        }

        this.initializeKB();
        logger.info(marker, "Data root directory: '{}'", getDataRootDir());
        logger.info(marker, "Indexer Factory: '{}'", indicesFactory.getClass().getName());
        logger.info(marker, "DS Service Endpoint: '{}'", mainConfig.getString("distributional-service.rest-url"));
        logger.info(marker, "★☆ {}, {} ({}) ★☆", Version.getCodeName(), Version.getBuildVersion(), Version.getBuildNumber());
        initialized = true;
    }

    public synchronized final void terminate() {
        if (!initialized) {
            throw new IllegalStateException("Not initialized");
        }

        indexers.values().forEach(Indexer::stop);
        searchers.values().forEach(Searcher::stop);

        luceneDirs.values().forEach(dir -> {
            try {
                dir.close();
            } catch (Exception e) {
                logger.error("Fail to close lucene index directory.", e);
            }
        });

        initialized = false;
    }

    private void initializeKB() {
        ConfigObject kbObj;
        try {
            kbObj = this.mainConfig.getObject("kb");
        } catch (ConfigException e) {
            throw new StarGraphException("No KB configured.", e);
        }

        for (Map.Entry<String, ConfigValue> kbEntry : kbObj.entrySet()) {
            final String kbName = kbEntry.getKey();
            Config kbCfg = this.mainConfig.getConfig(String.format("kb.%s", kbName));

            if (!kbCfg.getBoolean("enabled")) {
                logger.info(marker, "KB {} is disabled.", kbName);
            } else {
                ConfigObject typeObj = this.mainConfig.getObject(String.format("kb.%s.model", kbEntry.getKey()));
                for (Map.Entry<String, ConfigValue> typeEntry : typeObj.entrySet()) {
                    KBId kbId = KBId.of(kbName, typeEntry.getKey());
                    logger.info(marker, "Initializing {}", kbId);

                    Indexer indexer = this.indicesFactory.createIndexer(kbId, this);

                    if (indexer != null) {
                        indexer.start();
                        indexers.put(kbId, indexer);
                    }
                    else {
                        logger.warn("No indexer created for {}", kbId);
                    }


                    BaseSearcher searcher = this.indicesFactory.createSearcher(kbId, this);

                    if (searcher != null) {
                        searcher.start();
                        searchers.put(kbId, searcher);
                    }
                    else {
                        logger.warn("No searcher created for {}", kbId);
                    }
                }
            }
        }

        if (searchers.isEmpty()) {
            logger.warn(marker, "No KBs configured.");
        }
    }

    private List<? extends Config> getProcessorsCfg(KBId kbId) {
        String path = String.format("%s.processors", kbId.getTypePath());
        if (mainConfig.hasPath(path)) {
            return mainConfig.getConfigList(path);
        }
        return null;
    }

    private Config getDataProviderCfg(KBId kbId) {
        String path = String.format("%s.provider", kbId.getTypePath());
        return mainConfig.getConfig(path);
    }


    private IndicesFactory createIndicesFactory() {
        try {
            String className = getConfig().getString("index-store.factory.class");
            Class<?> providerClazz = Class.forName(className);
            Constructor<?> constructor = providerClazz.getConstructors()[0];
            return (IndicesFactory) constructor.newInstance();
        } catch (Exception e) {
            throw new StarGraphException("Can't initialize indexers.", e);
        }
    }

}
