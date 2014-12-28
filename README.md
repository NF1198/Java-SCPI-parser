Java-SCPI-parser
================

The SCPIParser library provides an easy to use SCPI-style command parser.

Refer to the SCPIParser class documentation for usage and examples.

Example Usage:

    class SimpleSCPIParser extends SCPIParser {
      public SimpleSCPIParser() {
        addHandler("*IDN?", this::IDN);
      }
    
      String IDN(String[] args) {
        return "Simple SCPI Parser";
      }
    }
     
    SimpleSCPIParser myParser = new SimpleSCPIParser();
      for (String result : myParser.accept("*IDN?")) {
        System.out.println(result);
      }
  
 
