package com.finalexec;

import com.finalexec.api.RuntimeMetadataValidationController;
import com.finalexec.npdev.service.RuntimeMetadataValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuntimeMetadataValidationControllerStandaloneTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RuntimeMetadataValidationController controller =
                new RuntimeMetadataValidationController(new RuntimeMetadataValidationService());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void validateReturnsSemanticDiagnosticsForBrokenReferences() throws Exception {
        mockMvc.perform(post("/api/runtime/metadata/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "namespace": "trial.validation",
                                  "dslVersion": "1.0.0",
                                  "version": "1.0",
                                  "concepts": [
                                    {
                                      "name": "Appointment",
                                      "ui": {
                                        "label": "Appointment"
                                      },
                                      "fields": [
                                        {
                                          "name": "id",
                                          "type": "uuid",
                                          "id": true,
                                          "required": true
                                        },
                                        {
                                          "name": "patientId",
                                          "type": "reference",
                                          "reference": {
                                            "target": "Patient"
                                          },
                                          "ui": {
                                            "label": "Patient"
                                          }
                                        }
                                      ],
                                      "invariants": []
                                    }
                                  ],
                                  "capabilities": [],
                                  "bindings": [],
                                  "events": [],
                                  "orchestrationRules": [],
                                  "flows": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errorCount").value(1))
                .andExpect(jsonPath("$.diagnostics[0].layer").value("semantic"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("unknown_reference_target"))
                .andExpect(jsonPath("$.diagnostics[0].path").value("entities[Appointment].fields[patientId]"))
                .andExpect(jsonPath("$.diagnostics[0].suggestedFix").exists());
    }

    @Test
    void validateReturnsValidTrueForSemanticallyHealthyModel() throws Exception {
        mockMvc.perform(post("/api/runtime/metadata/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "namespace": "trial.validation",
                                  "dslVersion": "1.0.0",
                                  "version": "1.0",
                                  "concepts": [
                                    {
                                      "name": "Patient",
                                      "ui": {
                                        "label": "Patient"
                                      },
                                      "fields": [
                                        {
                                          "name": "id",
                                          "type": "uuid",
                                          "id": true,
                                          "required": true
                                        },
                                        {
                                          "name": "firstName",
                                          "type": "string",
                                          "ui": {
                                            "label": "First name"
                                          }
                                        }
                                      ],
                                      "invariants": []
                                    }
                                  ],
                                  "capabilities": [],
                                  "bindings": [],
                                  "events": [],
                                  "orchestrationRules": [],
                                  "flows": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errorCount").value(0));
    }
}

