package dev.healthbridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class Gap6ReproductionTest {
    private final V011KnownGapsTest original = new V011KnownGapsTest();
    @Rule public final TemporaryFolder temporary = original.getTemporary();

    @Test public void originalNewestThreeAdmissionFixture() {
        original.gap6ThreeProvenDoneGenerationsPreventNextEnvelopeAdmission();
    }
}
