package io.github.jaronie.centroidFinder;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Selects the largest component that meets a minimum pixel-count threshold.
 */
public final class LargestComponentSelector {
    private final int minimumSize;

    public LargestComponentSelector(int minimumSize) {
        if (minimumSize < 1) {
            throw new IllegalArgumentException("Minimum component size must be positive.");
        }
        this.minimumSize = minimumSize;
    }

    /**
     * Returns the largest qualifying component, or empty when none meet the minimum.
     * Equal-size components retain the group's existing order as the tie-breaker.
     */
    public Optional<Group> select(List<Group> groups) {
        Objects.requireNonNull(groups, "Groups cannot be null.");
        return groups.stream()
                .filter(group -> group.size() >= minimumSize)
                .max(Comparator.comparingInt(Group::size));
    }
}
