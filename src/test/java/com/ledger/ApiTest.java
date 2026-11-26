package com.ledger;

import com.ledger.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end over real HTTP: JSON shape, status codes and error codes. */
class ApiTest extends IntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    @Autowired TestRestTemplate http;

    @Test
    void happyPathOverHttp() {
        String a = createAccount("USD");
        String b = createAccount("USD");

        assertThat(post("/accounts/" + a + "/deposits", key(), "{\"amount\":10000,\"currency\":\"USD\"}").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        String idemKey = key();
        String body = "{\"fromAccountId\":\"" + a + "\",\"toAccountId\":\"" + b + "\",\"amount\":2500,\"currency\":\"USD\"}";
        ResponseEntity<Map<String, Object>> first = post("/transactions", idemKey, body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(first.getBody()).containsEntry("status", "POSTED").containsEntry("sourceAmount", 2500);
        assertThat((List<?>) first.getBody().get("entries")).hasSize(2);

        ResponseEntity<Map<String, Object>> replay = post("/transactions", idemKey, body);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().get("id")).isEqualTo(first.getBody().get("id"));

        Map<String, Object> accountA = get("/accounts/" + a).getBody();
        assertThat(accountA).containsEntry("balance", 7500).containsEntry("availableBalance", 7500);
        Map<String, Object> accountB = get("/accounts/" + b).getBody();
        assertThat(accountB).containsEntry("balance", 2500).containsEntry("heldBalance", 2500);

        ResponseEntity<List<Map<String, Object>>> history = http.exchange("/accounts/" + a + "/transactions",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(history.getBody()).extracting(m -> m.get("amount")).containsExactly(10000, -2500);

        String txId = (String) first.getBody().get("id");
        ResponseEntity<Map<String, Object>> reversal = post("/transactions/" + txId + "/reversal", null, "{\"reason\":\"oops\"}");
        assertThat(reversal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(get("/transactions/" + txId).getBody()).containsEntry("status", "REVERSED");
        assertThat(post("/transactions/" + txId + "/reversal", null, "{}").getBody()).containsEntry("code", "ALREADY_REVERSED");

        Map<String, Object> integrityReport = get("/admin/integrity-check").getBody();
        assertThat(integrityReport).containsEntry("healthy", true);
    }

    @Test
    void rejectsFractionalAndNonNumericAmounts() {
        String a = createAccount("USD");
        ResponseEntity<Map<String, Object>> fractional = post("/accounts/" + a + "/deposits", key(), "{\"amount\":12.5,\"currency\":\"USD\"}");
        assertThat(fractional.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fractional.getBody()).containsEntry("code", "MALFORMED_REQUEST");

        ResponseEntity<Map<String, Object>> asString = post("/accounts/" + a + "/deposits", key(), "{\"amount\":\"100\",\"currency\":\"USD\"}");
        assertThat(asString.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map<String, Object>> negative = post("/accounts/" + a + "/deposits", key(), "{\"amount\":-1,\"currency\":\"USD\"}");
        assertThat(negative.getBody()).containsEntry("code", "VALIDATION_FAILED");

        assertThat(get("/accounts/" + a).getBody()).containsEntry("balance", 0);
    }

    @Test
    void errorStatusCodes() {
        String a = createAccount("USD");
        String b = createAccount("USD");
        String transfer = "{\"fromAccountId\":\"" + a + "\",\"toAccountId\":\"" + b + "\",\"amount\":1,\"currency\":\"USD\"}";

        ResponseEntity<Map<String, Object>> noKey = post("/transactions", null, transfer);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noKey.getBody()).containsEntry("code", "MISSING_HEADER");

        ResponseEntity<Map<String, Object>> broke = post("/transactions", key(), transfer);
        assertThat(broke.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(broke.getBody()).containsEntry("code", "INSUFFICIENT_FUNDS");

        assertThat(get("/accounts/" + UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/accounts/not-a-uuid").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map<String, Object>> badCurrency = post("/accounts", null, "{\"name\":\"x\",\"currency\":\"ABC\"}");
        assertThat(badCurrency.getBody()).containsEntry("code", "UNSUPPORTED_CURRENCY");

        ResponseEntity<Map<String, Object>> badPeriod = post("/admin/reconciliations", null, "{\"period\":\"2025-13\"}");
        assertThat(badPeriod.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void edgeInputsAreRejectedCleanly() {
        String a = createAccount("USD");
        // Above the per-request cap (10^15), and beyond the long range: both 400, never a 500.
        assertThat(post("/accounts/" + a + "/deposits", key(), "{\"amount\":1000000000000001,\"currency\":\"USD\"}")
                .getBody()).containsEntry("code", "VALIDATION_FAILED");
        assertThat(post("/accounts/" + a + "/deposits", key(), "{\"amount\":99999999999999999999,\"currency\":\"USD\"}")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/accounts/" + a + "/deposits", key(), "{\"amount\":10,\"currency\":\"USD\",\"extra\":1}")
                .getBody()).containsEntry("code", "MALFORMED_REQUEST");
        assertThat(post("/accounts", null, "{\"name\":\"　\",\"currency\":\"USD\"}")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Unknown route and wrong method still answer in problem+json with a code.
        assertThat(get("/nope").getBody()).containsEntry("code", "NOT_FOUND");
        assertThat(http.exchange("/accounts/" + a, HttpMethod.DELETE, null, MAP).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(get("/accounts/" + a).getBody()).containsEntry("balance", 0);
    }

    @Test
    void jsonEdgeCasesNeverMoveMoney() {
        String a = createAccount("USD");
        String b = createAccount("USD");
        post("/accounts/" + a + "/deposits", key(), "{\"amount\":1000,\"currency\":\"USD\"}");
        String transferTo = "{\"fromAccountId\":\"" + a + "\",\"toAccountId\":\"" + b + "\",\"currency\":\"USD\",\"amount\":";

        assertThat(post("/transactions", key(), transferTo + "null}").getBody()).containsEntry("code", "VALIDATION_FAILED");
        assertThat(post("/transactions", key(), transferTo + "1.0}").getBody()).containsEntry("code", "MALFORMED_REQUEST");
        assertThat(post("/transactions", key(), transferTo + "true}").getBody()).containsEntry("code", "MALFORMED_REQUEST");
        assertThat(post("/transactions", key(), transferTo + "0}").getBody()).containsEntry("code", "VALIDATION_FAILED");
        assertThat(post("/transactions", key(), "").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/transactions", key(), "{\"amount\":5}").getBody()).containsEntry("code", "VALIDATION_FAILED");

        String longText = "x".repeat(256);
        assertThat(post("/transactions", key(), transferTo + "1,\"description\":\"" + longText + "\"}").getBody())
                .containsEntry("code", "VALIDATION_FAILED");
        assertThat(post("/transactions", key(), transferTo + "1,\"description\":\"" + longText.substring(1) + "\"}")
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);   // exactly 255 characters is allowed

        assertThat(get("/accounts/" + a).getBody()).containsEntry("balance", 999);   // only the valid 1-cent transfer moved
    }

    @Test
    void fxRatesCanBeReadAndSetExactly() {
        assertThat(get("/fx-rates/usd/sgd").getBody()).containsEntry("rate", "1.34500000");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> put = http.exchange("/fx-rates/GBP/SGD", HttpMethod.PUT,
                new HttpEntity<>("{\"rate\":\"1.700000000\"}", headers), MAP);   // trailing zeros are exact
        assertThat(put.getBody()).containsEntry("rateScaled", 170_000_000);
        assertThat(get("/fx-rates/EUR/JPY").getBody()).containsEntry("code", "FX_RATE_UNAVAILABLE");
    }

    @Test
    void listsAccountsWithHeldSplitAndCurrencies() {
        String a = createAccount("USD");
        String b = createAccount("USD");
        post("/accounts/" + a + "/deposits", key(), "{\"amount\":900,\"currency\":\"USD\"}");
        post("/transactions", key(), "{\"fromAccountId\":\"" + a + "\",\"toAccountId\":\"" + b + "\",\"amount\":300,\"currency\":\"USD\"}");

        ResponseEntity<List<Map<String, Object>>> list = http.exchange("/accounts", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        Map<String, Object> listedB = list.getBody().stream().filter(m -> b.equals(m.get("id"))).findFirst().orElseThrow();
        // The list must agree with the single-account view.
        assertThat(listedB).containsEntry("balance", 300).containsEntry("heldBalance", 300).containsEntry("availableBalance", 0);
        assertThat(list.getBody()).anySatisfy(m -> assertThat(m).containsEntry("systemRole", "EXTERNAL"));
        assertThat(list.getBody().get(0)).containsEntry("type", "CUSTOMER");   // customers first

        ResponseEntity<List<Map<String, Object>>> ccy = http.exchange("/currencies", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(ccy.getBody()).anySatisfy(m -> assertThat(m).containsEntry("code", "JPY").containsEntry("minorUnits", 0));
    }

    @Test
    void holdIsVisibleOverHttp() {
        String a = createAccount("USD");
        String b = createAccount("USD");
        post("/accounts/" + a + "/deposits", key(), "{\"amount\":500,\"currency\":\"USD\"}");
        post("/transactions", key(), "{\"fromAccountId\":\"" + a + "\",\"toAccountId\":\"" + b + "\",\"amount\":500,\"currency\":\"USD\"}");
        assertThat(get("/accounts/" + b).getBody()).containsEntry("availableBalance", 0);
        clock.advance(Duration.ofMinutes(10));
        assertThat(get("/accounts/" + b).getBody()).containsEntry("availableBalance", 500);
    }

    // ------------------------------------------------------------------------------------------

    private String createAccount(String currency) {
        ResponseEntity<Map<String, Object>> r = post("/accounts", null, "{\"name\":\"api\",\"currency\":\"" + currency + "\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private ResponseEntity<Map<String, Object>> post(String path, String idempotencyKey, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) headers.set("Idempotency-Key", idempotencyKey);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> get(String path) {
        return http.exchange(path, HttpMethod.GET, null, MAP);
    }
}
