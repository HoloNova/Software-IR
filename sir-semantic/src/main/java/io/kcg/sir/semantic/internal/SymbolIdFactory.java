package io.kcg.sir.semantic.internal;

import io.kcg.sir.semantic.symbol.SymbolId;
import java.nio.charset.StandardCharsets;

final class SymbolIdFactory {
   private SymbolIdFactory() {
   }

   static String encode(String value) {
      StringBuilder encoded = new StringBuilder();

      for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
         int unsigned = current & 255;
         if ((unsigned < 97 || unsigned > 122)
            && (unsigned < 65 || unsigned > 90)
            && (unsigned < 48 || unsigned > 57)
            && unsigned != 45
            && unsigned != 95
            && unsigned != 46
            && unsigned != 126) {
            encoded.append('%').append("%02X".formatted(unsigned));
         } else {
            encoded.append((char)unsigned);
         }
      }

      return encoded.toString();
   }

   static SymbolId primitive(String name) {
      return new SymbolId("sir://primitive/" + encode(name));
   }

   static SymbolId projectScope(String softwareName) {
      return new SymbolId("sir://" + encode(softwareName));
   }

   static SymbolId declaration(String softwareName, String kind, String name) {
      return new SymbolId("sir://" + encode(softwareName) + "/" + encode(kind) + "/" + encode(name));
   }

   static SymbolId entityIdentity(String softwareName, String entityName) {
      return new SymbolId("sir://" + encode(softwareName) + "/entity/" + encode(entityName) + "/identity");
   }

   static SymbolId entityField(String softwareName, String entityName, String fieldName) {
      return new SymbolId("sir://" + encode(softwareName) + "/entity/" + encode(entityName) + "/field/" + encode(fieldName));
   }

   static SymbolId inputField(String softwareName, String inputName, String fieldName) {
      return new SymbolId("sir://" + encode(softwareName) + "/input/" + encode(inputName) + "/field/" + encode(fieldName));
   }

   static SymbolId viewField(String softwareName, String viewName, String fieldName) {
       return new SymbolId("sir://" + encode(softwareName) + "/view/" + encode(viewName) + "/field/" + encode(fieldName));
   }

   static SymbolId enumMember(String softwareName, String enumName, String memberName) {
      return new SymbolId("sir://" + encode(softwareName) + "/enum/" + encode(enumName) + "/member/" + encode(memberName));
   }

   static SymbolId variable(String softwareName, String capabilityName, String varName) {
      return new SymbolId("sir://" + encode(softwareName) + "/capability/" + encode(capabilityName) + "/var/" + encode(varName));
   }

   static SymbolId stepScope(String softwareName, String capabilityName, String stepNodeId) {
      return new SymbolId("sir://" + encode(softwareName) + "/capability/" + encode(capabilityName) + "/step/" + encode(stepNodeId));
   }

   static SymbolId stepVariable(String softwareName, String capabilityName, String stepNodeId, String varName) {
      return new SymbolId("sir://" + encode(softwareName) + "/capability/" + encode(capabilityName) + "/step/" + encode(stepNodeId) + "/var/" + encode(varName));
   }
}
