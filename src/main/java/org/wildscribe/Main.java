package org.wildscribe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.jboss.galleon.api.config.GalleonFeaturePackConfig;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.FeaturePackLocation.FPID;
import org.jboss.galleon.universe.FeaturePackLocation.ProducerSpec;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.wildfly.glow.Layer;
import org.wildfly.glow.LayerMapping;
import org.wildfly.glow.LayerMapping.RULE;
import org.wildfly.glow.ProvisioningUtils;
import org.wildfly.glow.Space;
import org.wildfly.glow.WildFlyMavenMetadataProvider;
import org.wildfly.glow.maven.MavenResolver;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

public class Main {

    private static Map<FeaturePackLocation.FPID, Set<FeaturePackLocation.ProducerSpec>> defaultSpaceFpDependencies = new HashMap<>();

    public static void main(String[] args) throws Exception {
        MavenRepoManager mavenRepoManager = MavenResolver.newMavenResolver();
        Path tmpMetadataDirectory = Files.createTempDirectory("wildfly-glow-metadata");

        WildFlyMavenMetadataProvider provider = new WildFlyMavenMetadataProvider(mavenRepoManager,
                tmpMetadataDirectory);

        List<Space> spaces = provider.getAllSpaces();
        spaces.add(0, Space.DEFAULT);

        Map<String, List<org.wildscribe.ExecutionContext>> versions = new TreeMap<>();
        for (String version : provider.getAllVersions()) {
            versions.put(version, new ArrayList<>());

            String[] executionContexts = new String[] { "bare-metal", "cloud" };
            for (String executionContext : executionContexts) {
                List<org.wildscribe.Space> wildFlySpaces = new ArrayList<>();
                ProvisioningUtils.ProvisioningConsumer consumer = new ProvisioningUtils.ProvisioningConsumer() {
                    @Override
                    public void consume(Space space, GalleonProvisioningConfig provisioning, Map<String, Layer> all,
                            LayerMapping mapping,
                            Map<FeaturePackLocation.FPID, Set<FeaturePackLocation.ProducerSpec>> fpDependencies)
                            throws Exception {
                        if (Space.DEFAULT.equals(space)) {
                            defaultSpaceFpDependencies = fpDependencies;
                        }
                        org.wildscribe.Space wildflySpace = generate(space, fpDependencies, executionContext, version,
                                all,
                                mapping, provisioning, false, false, null);
                        wildFlySpaces.add(wildflySpace);

                    }
                };

                for (Space space : spaces) {
                    try {
                        ProvisioningUtils.traverseProvisioning(space, consumer, executionContext, null, false, version,
                                false, null,
                                mavenRepoManager, provider);
                    } catch (Exception exception) {
                        System.err.println("Unable to resolve with version=" + version + ", executionContext=" + executionContext + ", space=" + space.getName());
                    }
                }
                if (!wildFlySpaces.isEmpty()) {
                    versions.get(version).add(new ExecutionContext(executionContext, wildFlySpaces));
                }
            }

        }

        dump(versions);
    }

    private static void dump(Map<String, List<org.wildscribe.ExecutionContext>> versions) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory().enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE));
        mapper.setSerializationInclusion(Include.NON_NULL);
        mapper.setSerializationInclusion(Include.NON_EMPTY);
        String path = "target/versions.yaml";
        mapper.writeValue(new java.io.File(path), versions);
        System.out.println("✅ Versions exported to " + path);
    }

    private static org.wildscribe.Space generate(Space space,
            Map<FeaturePackLocation.FPID, Set<FeaturePackLocation.ProducerSpec>> fpDependencies,
            String context, String serverVersion, Map<String, Layer> allLayers,
            LayerMapping mapping, GalleonProvisioningConfig config, boolean isLatest, boolean techPreview,
            Path provisioningXml) throws Exception {
        if (config == null) {
            return new org.wildscribe.Space(space.getName(), space.getDescription(), Collections.emptyList());
        }

        List<org.wildscribe.FeaturePack> featurePacks = new ArrayList<>();

        Set<FeaturePackLocation.ProducerSpec> topLevel = new LinkedHashSet<>();
        Map<ProducerSpec, FPID> featurepacks = new LinkedHashMap<>();
        for (GalleonFeaturePackConfig fp : config.getFeaturePackDeps()) {
            topLevel.add(fp.getLocation().getProducer());
            for (FPID fpid : fpDependencies.keySet()) {
                if (fpid.getProducer().equals(fp.getLocation().getProducer())) {
                    featurepacks.put(fp.getLocation().getProducer(), fpid);
                    break;
                }
            }
        }
        for (ProducerSpec p : featurepacks.keySet()) {
            FPID id = featurepacks.get(p);
            Set<FeaturePackLocation.ProducerSpec> deps = fpDependencies.get(id);
            Set<org.wildscribe.Layer> layers = new HashSet<>();
            for (Layer l : allLayers.values()) {
                if (l.getFeaturePacks().contains(id)) {
                    Map<org.wildscribe.Rule, Set<String>> rules = new HashMap<>();
                    for (Entry<RULE, Set<String>> entry : l.getMatchingRules().entrySet()) {
                        rules.put(new org.wildscribe.Rule(entry.getKey().toString()), entry.getValue());
                    }
                    String description = l.getProperties().get("org.wildfly.rule.add-on-description");
                    Set<String> dependencies = l.getDependencies().stream().map(Layer::getName)
                            .collect(Collectors.toSet());
                    layers.add(new org.wildscribe.Layer(l.getName(), description, dependencies, l.getProperties()));
                }
                if (deps != null) {
                    for (FeaturePackLocation.ProducerSpec dep : deps) {
                        boolean inDefaultSpace = false;
                        if (!Space.DEFAULT.equals(space)) {
                            for (FPID fpid : defaultSpaceFpDependencies.keySet()) {
                                if (fpid.getProducer().equals(dep)) {
                                    inDefaultSpace = true;
                                    break;
                                }
                            }
                        }
                        if (!topLevel.contains(dep) && !inDefaultSpace) {
                            for (FeaturePackLocation.FPID fpid : l.getFeaturePacks()) {
                                if (fpid.getProducer().equals(dep)) {
                                    Map<org.wildscribe.Rule, Set<String>> rules = new HashMap<>();
                                    for (Entry<RULE, Set<String>> entry : l.getMatchingRules().entrySet()) {
                                        rules.put(new org.wildscribe.Rule(entry.getKey().toString()), entry.getValue());
                                    }
                                    String description = l.getProperties().get("org.wildfly.rule.add-on-description");
                                    Set<String> dependencies = l.getDependencies().stream().map(Layer::getName)
                                            .collect(Collectors.toSet());
                                    layers.add(new org.wildscribe.Layer(l.getName(), description, dependencies,
                                            l.getProperties()));
                                }
                            }
                        }
                    }
                }
            }
            featurePacks.add(new org.wildscribe.FeaturePack(id.toString(), layers));

            topLevel.addAll(deps);
        }

        return new org.wildscribe.Space(space.getName(), space.getDescription(), featurePacks);

    }

}