package io.kcg.sir.application.conformance;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** G8 checks share the existing Money application's build and lifecycle; no second server is started. */
final class DecimalSumChecks {
    static final String FIXTURE_RESOURCE = "/conformance/mysql/ExactAggregateWarningFixture.java.txt";
    static final String WARNING_ROUTE = "/api/fixture/aggregate-warning";
    private DecimalSumChecks() {}

    static void installWarningFixture(BusinessSliceHarness s) throws Exception {
        installFixture(s, FIXTURE_RESOURCE, "ExactAggregateWarningFixture.java");
    }

    static void installFixture(BusinessSliceHarness s, String resource, String fileName) throws Exception {
        byte[] source;
        try (var input = DecimalSumChecks.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing aggregate warning fixture");
            source = input.readAllBytes();
        }
        var path = s.projectRoot().resolve("src/main/java/example/money/fixture/" + fileName);
        Files.createDirectories(path.getParent());
        Files.write(path, source);
        // The run's evidence directory is created by whoever first writes into it, not by the constructor:
        // the installer runs before the build, so it must not assume the later log writers created it.
        Files.createDirectories(s.evidenceRoot());
        Files.write(s.evidenceRoot().resolve(fileName + ".txt"), source);
        s.reportLine("TEST_FIXTURE_ONLY aggregate warning probe sha256="
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source))
                + "; not part of the source/digest of normal product generation");
    }

    static void verify(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        var before = s.rowsOf(observer, "measurement");
        var tags = s.rowsOf(observer, "measurement_tag");
        total(s,http,"/measurements/total?owner=1","3.00","scope excludes removed rows; nulls ignored and signed amounts exact");
        total(s,http,"/measurements/total?owner=1&minimum=1.2300","3.57","optional bound input keeps exact trailing zeros");
        total(s,http,"/measurements/total?owner=2","999.99","request owner is bound independently from the entity scope");
        total(s,http,"/measurements/total?owner=3","0","all-null input is zero");
        total(s,http,"/measurements/total?owner=4","0","empty set is zero");
        total(s,http,"/measurements/total?owner=5","199999999999999999.98","total is not restricted to one Money row's precision");
        total(s,http,"/measurements/tagged?owner=1&tag=target","3.57","any filters without duplicating a multiply-tagged measurement");
        total(s,http,"/measurements/tagged?owner=1&tag="+encode("' OR 1=1 --"),"0","correlated request value stays a bound argument");
        total(s,http,"/measurements/literal?owner=1&tag="+encode("_%"),"1.23","containsLiteral still escapes SQL wildcards");
        total(s,http,"/measurements/average?owner=1","1.00","the average ignores nulls: (1.23 + 2.34 - 0.57) / 3");
        total(s,http,"/measurements/average?owner=1&minimum=1.2300","1.79","1.785 rounds half up to the cent");
        total(s,http,"/measurements/average?owner=3","0","an all-null selection averages to zero");
        total(s,http,"/measurements/average?owner=4","0","an empty selection averages to zero");
        total(s,http,"/measurements/average?owner=5","99999999999999999.99","the average of two large equal amounts is exact");
        warningPolicy(s,http,"strict session");
        s.check("aggregate reads never modify source rows or tags",before.equals(s.rowsOf(observer,"measurement"))
                && tags.equals(s.rowsOf(observer,"measurement_tag")),s.rowsOf(observer,"measurement").toString());
    }

    static void warningPolicy(BusinessSliceHarness s,HttpAssertionClient http,String mode) throws Exception {
        var ordinary=http.get(s.baseUrl()+WARNING_ROUTE+"?marked=false",Map.of());
        s.record("G8 fixture unmarked CAST ("+mode+")",ordinary);
        if(s.expectStatus("unmarked fixture exposes a real server precision warning ("+mode+")",ordinary,200))
            number(s,ordinary,"9.99","control query demonstrates MySQL's saturated decimal result");
        var protectedQuery=http.get(s.baseUrl()+WARNING_ROUTE+"?marked=true",Map.of());
        s.record("G8 fixture protected CAST ("+mode+")",protectedQuery);
        s.expectStatus("marked aggregate refuses its actual Statement warning ("+mode+")",protectedQuery,500);
        s.check("aggregate failure does not expose SQL or table details",!protectedQuery.body().contains("CAST(")
                && !protectedQuery.body().contains("measurement"),protectedQuery.body());
        total(s,http,"/measurements/total?owner=1","3.00","warning failure cannot poison a later valid aggregate ("+mode+")");
    }
    private static void total(BusinessSliceHarness s,HttpAssertionClient http,String path,String expected,String label) throws Exception {
        var response=http.get(s.baseUrl()+"/api"+path,Map.of());s.record("G8 "+label,response);
        if(s.expectStatus(label,response,200)) number(s,response,expected,label);
    }
    private static void number(BusinessSliceHarness s,HttpAssertionClient.Response response,String expected,String label) {
        boolean matches;
        try {matches=new BigDecimal(response.body().trim()).compareTo(new BigDecimal(expected))==0;}
        catch(NumberFormatException invalid){matches=false;}
        s.check(label+" = "+expected,matches,response.body());
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
