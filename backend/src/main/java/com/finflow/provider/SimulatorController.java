package com.finflow.provider;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/simulator")
@Tag(name = "Simulator")
public class SimulatorController {

    private final SimulatorProperties properties;

    public SimulatorController(SimulatorProperties properties) {
        this.properties = properties;
    }

    public record ScenarioResponse(String token, String label, String description, SimulationScenario.Outcome outcome) {
    }

    public record ScenariosResponse(List<ScenarioResponse> scenarios, long latencyMs, long timeoutMs, boolean outage) {
    }

    @GetMapping("/scenarios")
    @Operation(summary = "Fake payment-method tokens and the simulated outcome each one triggers")
    public ScenariosResponse scenarios() {
        List<ScenarioResponse> scenarios = Arrays.stream(SimulationScenario.values())
                .map(s -> new ScenarioResponse(s.token(), s.label(), s.description(), s.outcome()))
                .toList();
        return new ScenariosResponse(scenarios, properties.latencyMs(), properties.timeoutMs(), properties.outage());
    }
}
