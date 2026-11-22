package uz.sentinel.ad.ledger;

import java.util.List;

/** Everything sealed that a holder (player, container, ground item...) contained at one moment. */
public record Scan(String id, List<Sighting> sightings) {
    public boolean contains(String serial) {
        for (Sighting s : sightings) if (s.serial().equals(serial)) return true;
        return false;
    }

    public int count(String serial) {
        int n = 0;
        for (Sighting s : sightings) if (s.serial().equals(serial)) n++;
        return n;
    }
}
