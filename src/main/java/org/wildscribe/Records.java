package org.wildscribe;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @param name of the context (bare-metal, openshift)
 * @param spaces Spaces
 */
record ExecutionContext(@JsonProperty("execution-context") String executionContext, List<Space> spaces) {}

/**
 * @param name name of the space
 * @param description description of the space
 * @param featurePacks Feature packs that are in this space
 */
record Space(String name, String description, @JsonProperty("feature-packs") List<FeaturePack> featurePacks) {}

/**
 * @param id Maven GAV of the feature pack
 * @param layers Layers provided by this feature pack 
 */
record FeaturePack(String id, Set<Layer> layers) {}

/**
 * @param name name of the layer
 * @param description Human-readable description of the layer
 * @param dependencies the layers that this layer is depending on
 * @param properties the properties of this layer
*/
record Layer(String name, String description, Set<String> dependencies, Map<String, String> properties) {}

/**
 * @param name name of the rule
*/
record Rule(String name) {}
