package io.kcg.sir.application.conformance;

import java.nio.file.Path;
import java.util.Set;

/**
 * The directory and file paths observed under an evidence root during one
 * NOFOLLOW traversal.
 *
 * <p>Both sets carry every path that exists on disk, so the scanner can compare
 * them bidirectionally against the inventory's registered directories and
 * finalized files: an entry only on disk is an unknown object, and an entry only
 * in the inventory is a missing one. Either direction is a dirty scan.
 *
 * @param directories every directory path seen during the traversal
 * @param files       every regular file path seen during the traversal
 */
final class DiskTree {

    final Set<Path> directories;
    final Set<Path> files;

    DiskTree(Set<Path> directories, Set<Path> files) {
        this.directories = Set.copyOf(directories);
        this.files = Set.copyOf(files);
    }
}
