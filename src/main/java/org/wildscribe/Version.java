package org.wildscribe;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record Version(String version, boolean techPreview, String space, List<FeaturePack> featurePacks) {

    record FeaturePack(String id, Set<Layer> layers) {

        record Layer(String name, Map<Rule, Set<String>> rules) {

            record Rule(String name) {}

        }
    }
}
