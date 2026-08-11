package io.kcg.sir.change.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ChangePlan(
   ChangeSet changeSet,
   List<ArtifactChange> artifactChanges,
   List<FileChange> fileChanges,
   List<ArtifactAddition> artifactAdditions,
   List<FileAddition> fileAdditions,
   List<ArtifactDeletion> artifactDeletions,
   List<FileDeletion> fileDeletions
) {
   public ChangePlan(
      ChangeSet changeSet,
      List<ArtifactChange> artifactChanges,
      List<FileChange> fileChanges,
      List<ArtifactAddition> artifactAdditions,
      List<FileAddition> fileAdditions,
      List<ArtifactDeletion> artifactDeletions,
      List<FileDeletion> fileDeletions
   ) {
      Objects.requireNonNull(changeSet, "changeSet");
      artifactChanges = List.copyOf(Objects.requireNonNull(artifactChanges, "artifactChanges"));
      fileChanges = List.copyOf(Objects.requireNonNull(fileChanges, "fileChanges"));
      artifactAdditions = List.copyOf(Objects.requireNonNull(artifactAdditions, "artifactAdditions"));
      fileAdditions = List.copyOf(Objects.requireNonNull(fileAdditions, "fileAdditions"));
      artifactDeletions = List.copyOf(Objects.requireNonNull(artifactDeletions, "artifactDeletions"));
      fileDeletions = List.copyOf(Objects.requireNonNull(fileDeletions, "fileDeletions"));
      boolean hasUpdates = !artifactChanges.isEmpty() || !fileChanges.isEmpty();
      boolean hasAdditions = !artifactAdditions.isEmpty() || !fileAdditions.isEmpty();
      boolean hasDeletions = !artifactDeletions.isEmpty() || !fileDeletions.isEmpty();
      int nonEmptyFamilies = (hasUpdates ? 1 : 0) + (hasAdditions ? 1 : 0) + (hasDeletions ? 1 : 0);
      if (nonEmptyFamilies == 0) {
         throw new IllegalArgumentException("ChangePlan must contain at least one change, addition, or deletion");
      }

      if (nonEmptyFamilies > 1) {
         throw new IllegalArgumentException(
            "ChangePlan must not mix UPDATE family (artifactChanges/fileChanges), CREATE family (artifactAdditions/fileAdditions), or DELETE family (artifactDeletions/fileDeletions)"
         );
      }

      if (hasUpdates) {
         if (artifactChanges.isEmpty()) {
            throw new IllegalArgumentException("ChangePlan UPDATE family must contain at least one ArtifactChange");
         }

         if (fileChanges.isEmpty()) {
            throw new IllegalArgumentException("ChangePlan UPDATE family must contain at least one FileChange");
         }
      }

      if (hasAdditions) {
         if (artifactAdditions.isEmpty()) {
            throw new IllegalArgumentException("ChangePlan CREATE family must contain at least one ArtifactAddition");
         }

         if (fileAdditions.isEmpty()) {
            throw new IllegalArgumentException("ChangePlan CREATE family must contain at least one FileAddition");
         }

         List<ArtifactAddition> sortedArtifacts = new ArrayList<>(artifactAdditions);
         sortedArtifacts.sort(Comparator.comparing(a -> a.artifactId().value()));
         if (!sortedArtifacts.equals(artifactAdditions)) {
            throw new IllegalArgumentException("ChangePlan.artifactAdditions must be sorted by artifactId.value ascending");
         }

         List<FileAddition> sortedFiles = new ArrayList<>(fileAdditions);
         sortedFiles.sort(Comparator.comparing(FileAddition::relativePath));
         if (!sortedFiles.equals(fileAdditions)) {
            throw new IllegalArgumentException("ChangePlan.fileAdditions must be sorted by relativePath ascending");
         }

         List<FileAddition> flattened = new ArrayList<>();
         Set<String> seen = new LinkedHashSet<>();

         for (ArtifactAddition aa : artifactAdditions) {
            for (FileAddition fa : aa.fileAdditions()) {
               if (!seen.add(fa.relativePath())) {
                  throw new IllegalArgumentException("ChangePlan.fileAdditions contains duplicate relativePath: " + fa.relativePath());
               }

               flattened.add(fa);
            }
         }

         flattened.sort(Comparator.comparing(FileAddition::relativePath));
         if (!flattened.equals(fileAdditions)) {
            throw new IllegalArgumentException(
               "ChangePlan.fileAdditions must equal the flattened, deduplicated, relativePath-sorted view of artifactAdditions.fileAdditions"
            );
         }
      }

      if (hasDeletions) {
         if (artifactDeletions.isEmpty()) {
            throw new IllegalArgumentException("ChangePlan DELETE family must contain at least one ArtifactDeletion");
         }

         if (fileDeletions.isEmpty()) {
            throw new IllegalArgumentException("ChangePlan DELETE family must contain at least one FileDeletion");
         }

         List<ArtifactDeletion> sortedDelArtifacts = new ArrayList<>(artifactDeletions);
         sortedDelArtifacts.sort(Comparator.comparing(a -> a.artifactId().value()));
         if (!sortedDelArtifacts.equals(artifactDeletions)) {
            throw new IllegalArgumentException("ChangePlan.artifactDeletions must be sorted by artifactId.value ascending");
         }

         List<FileDeletion> sortedDelFiles = new ArrayList<>(fileDeletions);
         sortedDelFiles.sort(Comparator.comparing(FileDeletion::relativePath));
         if (!sortedDelFiles.equals(fileDeletions)) {
            throw new IllegalArgumentException("ChangePlan.fileDeletions must be sorted by relativePath ascending");
         }

         List<FileDeletion> flattenedDel = new ArrayList<>();
         Set<String> seenDel = new LinkedHashSet<>();

         for (ArtifactDeletion ad : artifactDeletions) {
            for (FileDeletion fd : ad.fileDeletions()) {
               if (!seenDel.add(fd.relativePath())) {
                  throw new IllegalArgumentException("ChangePlan.fileDeletions contains duplicate relativePath: " + fd.relativePath());
               }

               flattenedDel.add(fd);
            }
         }

         flattenedDel.sort(Comparator.comparing(FileDeletion::relativePath));
         if (!flattenedDel.equals(fileDeletions)) {
            throw new IllegalArgumentException(
               "ChangePlan.fileDeletions must equal the flattened, deduplicated, relativePath-sorted view of artifactDeletions.fileDeletions"
            );
         }
      }

      this.changeSet = changeSet;
      this.artifactChanges = artifactChanges;
      this.fileChanges = fileChanges;
      this.artifactAdditions = artifactAdditions;
      this.fileAdditions = fileAdditions;
      this.artifactDeletions = artifactDeletions;
      this.fileDeletions = fileDeletions;
   }

   public ChangePlan(ChangeSet changeSet, List<ArtifactChange> artifactChanges, List<FileChange> fileChanges) {
      this(changeSet, artifactChanges, fileChanges, List.of(), List.of());
   }

   public ChangePlan(
      ChangeSet changeSet,
      List<ArtifactChange> artifactChanges,
      List<FileChange> fileChanges,
      List<ArtifactAddition> artifactAdditions,
      List<FileAddition> fileAdditions
   ) {
      this(changeSet, artifactChanges, fileChanges, artifactAdditions, fileAdditions, List.of(), List.of());
   }
}
