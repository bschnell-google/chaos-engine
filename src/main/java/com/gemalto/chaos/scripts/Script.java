package com.gemalto.chaos.scripts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.core.io.Resource;

import java.util.Collection;

public interface Script {
    String getHealthCheckCommand ();

    String getSelfHealingCommand ();

    boolean isRequiresCattle ();

    String getScriptName ();

    boolean doesNotUseMissingDependencies (Collection<String> knownMissingDependencies);

    String getFinalizeCommand ();

    @JsonIgnore
    Resource getResource ();
}
