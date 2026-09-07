package com.cflint;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.cflint.api.CFLintAPI;
import com.cflint.api.CFLintResult;
import com.cflint.config.ConfigBuilder;
import com.cflint.exception.CFLintScanException;

/**
 * Modern CFML shapes must scan without PLUGIN_ERROR / PARSE_ERROR.
 */
@RunWith(Parameterized.class)
public class TestCFBugs_ModernSyntax {

    private final String caseName;
    private final String source;

    private CFLintAPI cfBugs;

    public TestCFBugs_ModernSyntax(final String caseName, final String source) {
        this.caseName = caseName;
        this.source = source;
    }

    @Parameters(name = "{0}")
    public static Object[][] cases() {
        return new Object[][] {
                { "block_arrow_struct_value",
                        "component {\n"
                                + "  function test() {\n"
                                + "    rows = [];\n"
                                + "    rptdetails = {\n"
                                + "      'heading' = 'iPOS',\n"
                                + "      'data' = seq.Map(rows, (row) => {\n"
                                + "        return [ row.id, row.name ];\n"
                                + "      })\n"
                                + "    };\n"
                                + "    return rptdetails;\n"
                                + "  }\n"
                                + "}\n" },
                { "elvis_member",
                        "component {\n"
                                + "  function test(required struct pat) {\n"
                                + "    return pat.uniqueno ?: '';\n"
                                + "  }\n"
                                + "}\n" },
                { "ternary_struct",
                        "component {\n"
                                + "  function test(any x) {\n"
                                + "    return isNull(x) ? '' : { value = x, html = '<b>#x#</b>' };\n"
                                + "  }\n"
                                + "}\n" },
                { "nested_quotes_interp",
                        "component {\n"
                                + "  function test(date ts) {\n"
                                + "    return '#DateFormat(ts,'YYYYMMDD')#';\n"
                                + "  }\n"
                                + "}\n" },
                { "queryexecute_doublehash",
                        "component {\n"
                                + "  function test(required numeric id) {\n"
                                + "    return QueryExecute(\""
                                + "SELECT mrn || COALESCE('##' || NULLIF(readmitcount, 1), '') AS mrn "
                                + "FROM patientinfo WHERE id = :id"
                                + "\", {\n"
                                + "      'id' = { cfsqltype = \"cf_sql_integer\", value = id }\n"
                                + "    });\n"
                                + "  }\n"
                                + "}\n" },
                { "loop_inside_arrow",
                        "component {\n"
                                + "  function test(query rptdata) {\n"
                                + "    return rptdata.Filter((row) => {\n"
                                + "      var score = { subscores = {} };\n"
                                + "      loop\n"
                                + "        collection = score.subscores\n"
                                + "        index = \"k\"\n"
                                + "        item = \"value\" {\n"
                                + "          if (value.score > 2 && value.completed) {\n"
                                + "            return true;\n"
                                + "          }\n"
                                + "        }\n"
                                + "      return false;\n"
                                + "    });\n"
                                + "  }\n"
                                + "}\n" },
                { "trailing_required_static",
                        "component {\n"
                                + "  function declOk(required boolean flag, required string name,) {\n"
                                + "    return true;\n"
                                + "  }\n"
                                + "  function callTrailing() {\n"
                                + "    return declOk(true, 'x',);\n"
                                + "  }\n"
                                + "  function staticCall() {\n"
                                + "    return CompName::runStatic();\n"
                                + "  }\n"
                                + "}\n" },
                { "combined_modern_syntax",
                        "component {\n"
                                + "  function test() {\n"
                                + "    rows = [];\n"
                                + "    rptdetails = {\n"
                                + "      'heading' = 'iPOS',\n"
                                + "      'data' = seq.Map(rows, (row) => { return [ row.id ]; })\n"
                                + "    };\n"
                                + "    x = pat.uniqueno ?: '';\n"
                                + "    out = isNull(x) ? '' : { value = x, html = 'y' };\n"
                                + "    s = '#DateFormat(ts,'YYYYMMDD')#';\n"
                                + "    q = QueryExecute(\"SELECT '##' AS mrn FROM dual\");\n"
                                + "    filtered = rows.Filter((row) => {\n"
                                + "      loop collection = row.subscores index = \"k\" item = \"v\" {\n"
                                + "        if (v.score > 2) { return true; }\n"
                                + "      }\n"
                                + "      return false;\n"
                                + "    });\n"
                                + "  }\n"
                                + "  function declOk(required boolean flag, required string name,) {\n"
                                + "    return CompName::runStatic(true, 'x',);\n"
                                + "  }\n"
                                + "}\n" },
        };
    }

    @Before
    public void setUp() throws Exception {
        cfBugs = new CFLintAPI(new ConfigBuilder().build());
    }

    @Test
    public void doesNotCrash() throws CFLintScanException {
        final CFLintResult lintresult = cfBugs.scan(source, caseName + ".cfc");
        assertNotNull(caseName, lintresult);
        final Map<String, List<BugInfo>> issues = lintresult.getIssues();
        assertFalse(caseName + " PLUGIN_ERROR: " + issues.get("PLUGIN_ERROR"),
                issues.containsKey("PLUGIN_ERROR"));
        assertFalse(caseName + " PARSE_ERROR: " + issues.get("PARSE_ERROR"),
                issues.containsKey("PARSE_ERROR"));
        assertTrue(issues != null);
    }
}
