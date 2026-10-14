package io.kcg.sir.projectgraph.internal;

/**
 * The one graph and canonical-document format. The values are the identifiers the format has always written (the second
 * generation, which records the source files), kept as they are so that an existing digest or document does not change.
 */
final class SnapshotVersion {
   /** Written in the canonical form and in the GRAPH record. */
   static final String GRAPH = "V0_2";
   /** Written in the HEADER record. */
   static final String FORMAT = "2";

   private SnapshotVersion() {
   }
}
