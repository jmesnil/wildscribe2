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
import org.wildscribe.Version.FeaturePack.Layer.Rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

public class Main {

    private static Map<FeaturePackLocation.FPID, Set<FeaturePackLocation.ProducerSpec>> defaultSpaceFpDependencies = new HashMap<>();

    public static void main(String[] args) throws Exception {
        MavenRepoManager mavenRepoManager = MavenResolver.newMavenResolver();
        Path tmpMetadataDirectory = Files.createTempDirectory("wildfly-glow-metadata");

        WildFlyMavenMetadataProvider provider = new WildFlyMavenMetadataProvider(mavenRepoManager,
                tmpMetadataDirectory);
        Set<String> versions = provider.getAllVersions();
        System.out.println(versions);

        List<Space> spaces = provider.getAllSpaces();
        spaces.add(0, Space.DEFAULT);
        for (Space space : spaces) {
            System.out.println(space.getName() + " -> " + space.getDescription());
        }

        String context = "bare-metal";
        List<Version> wildflyVersions = new ArrayList<>();
        for (String version : versions) {

            ProvisioningUtils.ProvisioningConsumer consumer = new ProvisioningUtils.ProvisioningConsumer() {
                @Override
                public void consume(Space space, GalleonProvisioningConfig provisioning, Map<String, Layer> all,
                        LayerMapping mapping,
                        Map<FeaturePackLocation.FPID, Set<FeaturePackLocation.ProducerSpec>> fpDependencies)
                        throws Exception {
                    if (Space.DEFAULT.equals(space)) {
                        defaultSpaceFpDependencies = fpDependencies;
                    }

                    Version wildflyVersion = generate(space, fpDependencies, context, version, all,
                    mapping, provisioning, false, false, null);

                    wildflyVersions.add(wildflyVersion);
                }
            };

            for (Space space : spaces) {
                try {
                    ProvisioningUtils.traverseProvisioning(space, consumer, context, null, false, version,
                            false, null,
                            mavenRepoManager, provider);
                } catch (Exception exception) {
                    System.err.println("Unable to resolve version " + version + ", space " + space.getName());
                }
            }

        }

        dump(wildflyVersions);
    }

    private static void dump(List<Version> versions) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.writeValue(new java.io.File("target/versions.yaml"), versions);
    }

    private static Version generate(Space space,
            Map<FeaturePackLocation.FPID, Set<FeaturePackLocation.ProducerSpec>> fpDependencies,
            String context, String serverVersion, Map<String, Layer> allLayers,
            LayerMapping mapping, GalleonProvisioningConfig config, boolean isLatest, boolean techPreview,
            Path provisioningXml) throws Exception {
        if (config == null) {
            return new Version(serverVersion, techPreview, space.getName(), Collections.emptyList());
        }

        List<org.wildscribe.Version.FeaturePack> featurePacks = new ArrayList<>();

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
            Set<org.wildscribe.Version.FeaturePack.Layer> layers = new HashSet<>();
            for (Layer l : allLayers.values()) {
                if (l.getFeaturePacks().contains(id)) {
                    Map<Rule, Set<String>> rules = new HashMap<>();
                    for (Entry<RULE, Set<String>> entry : l.getMatchingRules().entrySet()) {
                        rules.put(new Rule(entry.getKey().toString()), entry.getValue());
                    }
                    layers.add(new org.wildscribe.Version.FeaturePack.Layer(l.getName(), rules));
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
                                    Map<Rule, Set<String>> rules = new HashMap<>();
                                    for (Entry<RULE, Set<String>> entry : l.getMatchingRules().entrySet()) {
                                        rules.put(new Rule(entry.getKey().toString()), entry.getValue());
                                    }
                                    layers.add(new org.wildscribe.Version.FeaturePack.Layer(l.getName(), rules));
                                }
                            }
                        }
                    }
                }
            }
            featurePacks.add(new org.wildscribe.Version.FeaturePack(id.toString(), layers));

            topLevel.addAll(deps);
        }

        return new Version(serverVersion, techPreview, space.getName(), featurePacks);

    }

}