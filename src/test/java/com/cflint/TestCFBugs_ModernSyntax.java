package com.cflint;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.cflint.api.CFLintAPI;
import com.cflint.api.CFLintResult;
import com.cflint.config.ConfigBuilder;
import com.cflint.exception.CFLintScanException;

/**
 * Regression: modern CFML (elvis, ::, arrows) must scan without PLUGIN_ERROR.
 */
public class TestCFBugs_ModernSyntax {

    private CFLintAPI cfBugs;

    @Before
    public void setUp() throws Exception {
        cfBugs = new CFLintAPI(new ConfigBuilder().build());
    }

    @Test
    public void elvisMemberDoesNotThrow() throws CFLintScanException {
        final String src = "component {\n"
                + "  function test(required struct pat) {\n"
                + "    return pat.uniqueno ?: '';\n"
                + "  }\n"
                + "}\n";
        assertCleanScan(src, "elvis_member.cfc");
    }

    @Test
    public void staticInvocationDoesNotThrow() throws CFLintScanException {
        final String src = "component {\n"
                + "  function staticCall() {\n"
                + "    return CompName::runStatic();\n"
                + "  }\n"
                + "}\n";
        assertCleanScan(src, "trailing_required_static.cfc");
    }

    @Test
    public void arrowFunctionBodyDoesNotThrow() throws CFLintScanException {
        final String src = "component {\n"
                + "  function filterRows(required array rows) {\n"
                + "    return rows.Filter((row) => { return row.id; });\n"
                + "  }\n"
                + "}\n";
        assertCleanScan(src, "arrow_body.cfc");
    }

    @Test
    public void safeNavAndCombinedModernDoesNotThrow() throws CFLintScanException {
        final String src = "component {\n"
                + "  function go(required any obj, required struct pat) {\n"
                + "    var x = obj?.prop;\n"
                + "    var y = pat.uniqueno ?: '';\n"
                + "    return CompName::runStatic();\n"
                + "  }\n"
                + "}\n";
        assertCleanScan(src, "combined_modern.cfc");
    }

    private void assertCleanScan(final String src, final String filename) throws CFLintScanException {
        final CFLintResult lintresult = cfBugs.scan(src, filename);
        assertNotNull(lintresult);
        final Map<String, List<BugInfo>> issues = lintresult.getIssues();
        assertFalse("PLUGIN_ERROR for " + filename + ": " + issues.get("PLUGIN_ERROR"),
                issues.containsKey("PLUGIN_ERROR"));
        assertFalse("PARSE_ERROR for " + filename + ": " + issues.get("PARSE_ERROR"),
                issues.containsKey("PARSE_ERROR"));
        assertTrue(issues != null);
    }
}
