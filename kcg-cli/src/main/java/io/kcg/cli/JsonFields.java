package io.kcg.cli;

/** The two building blocks of the one-line JSON documents: a named value and a separator. */
final class JsonFields {
   private JsonFields() {
   }

   static void field(StringBuilder sb, String name, String value) {
      sb.append(JsonStringEncoder.encode(name)).append(':').append(JsonStringEncoder.encode(value));
   }

   static void field(StringBuilder sb, String name, long value) {
      sb.append(JsonStringEncoder.encode(name)).append(':').append(value);
   }

   static void comma(StringBuilder sb) {
      sb.append(',');
   }
}
