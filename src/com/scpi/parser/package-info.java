/**
 * The SCPIParser library provides an easy to use SCPI-style command parser.
 * <p>Refer to the {@link com.scpi.parser.SCPIParser} class documentation for usage and examples.</p>
 * <h3>Example Usage</h3>
 * <pre>
 * {@code class SimpleSCPIParser extends SCPIParser {
 *    public SimpleSCPIParser() {
 *      addHandler("*IDN?", this::IDN);
 *    }
 *
 *    String IDN(String[] args) {
 *          return "Simple SCPI Parser";
 *    }
 * }
 * 
 * SimpleSCPIParser myParser = new SimpleSCPIParser();
 * for (String result : myParser.accept("*IDN?")) {
 *   System.out.println(result);
 * }}
 * </pre>
 * 
 */
package com.scpi.parser;