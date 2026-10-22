package com.finflow.api;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class SecurityAndValidationIntegrationTest extends IntegrationTestBase {

    @Test
    void requestsWithoutAValidApiKeyAreRejectedWithProblemDetails() throws Exception {
        getJson(null, "/api/v1/payments")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        getJson("ff_test_not-a-real-key", "/api/v1/payments").andExpect(status().isUnauthorized());
    }

    @Test
    void merchantsCannotSeeEachOthersData() throws Exception {
        String alice = newMerchantKey();
        String bob = newMerchantKey();
        String orderId = createOrder(alice, 10_00);
        String paymentId = payAndRead(alice, orderId, "tok_success", "AUTOMATIC").read("$.id");

        getJson(bob, "/api/v1/orders/" + orderId).andExpect(status().isNotFound());
        getJson(bob, "/api/v1/payments/" + paymentId).andExpect(status().isNotFound());
        getJson(bob, "/api/v1/payments/" + paymentId + "/events").andExpect(status().isNotFound());
        refund(bob, paymentId, null).andExpect(status().isNotFound());
        pay(bob, orderId, "tok_success", "AUTOMATIC").andExpect(status().isNotFound());
        getJson(bob, "/api/v1/payments").andExpect(jsonPath("$.totalElements").value(0));
        getJson(bob, "/api/v1/ledger/entries").andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void invalidOrderIsRejectedWithFieldErrors() throws Exception {
        String key = newMerchantKey();
        postJson(key, "/api/v1/orders", """
                {"merchantReference":"bad ref!","amountMinor":-5,"currency":"BTC"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("amountMinor")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("currency")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("merchantReference")));
    }

    @Test
    void duplicateMerchantReferenceIsRejected() throws Exception {
        String key = newMerchantKey();
        String body = """
                {"merchantReference":"DUP-1","amountMinor":100,"currency":"USD"}""";
        postJson(key, "/api/v1/orders", body).andExpect(status().isCreated());
        postJson(key, "/api/v1/orders", body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void cardNumbersAndUnknownTokensAreRejected() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 10_00);

        pay(key, orderId, "4242424242424242", "AUTOMATIC")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        pay(key, orderId, "tok_unknown", "AUTOMATIC")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PAYMENT_METHOD"));
    }

    @Test
    void paymentRequiresAWellFormedIdempotencyKey() throws Exception {
        String key = newMerchantKey();
        String body = "{\"orderId\":\"" + createOrder(key, 100) + "\",\"paymentMethodToken\":\"tok_success\"}";

        postJson(key, "/api/v1/payments", body, null).andExpect(status().isBadRequest());
        postJson(key, "/api/v1/payments", body, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void malformedJsonAndBadPagingAreClientErrors() throws Exception {
        String key = newMerchantKey();
        postJson(key, "/api/v1/orders", "{not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        getJson(key, "/api/v1/payments?size=1000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        getJson(key, "/api/v1/payments/" + UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void demoSessionIssuesAWorkingKeyForTheDemoMerchant() throws Exception {
        String apiKey = json(postJson(null, "/api/v1/demo/session", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulation").value(true))
                .andReturn()).read("$.apiKey");

        getJson(apiKey, "/api/v1/merchants/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demo").value(true));
    }

    @Test
    void openApiDocumentIsPublished() throws Exception {
        getJson(null, "/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/payments']").exists());
    }
}
