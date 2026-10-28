package com.unicarona.privacy;

import com.unicarona.privacy.service.ParticipantIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticipantIdentityTest {
    @Test void preservesUsefulRecognitionWithoutPublishingAllSurnames() {
        assertEquals("Maria O.",ParticipantIdentity.label("Maria Clara de Oliveira"));
        assertEquals("João S.",ParticipantIdentity.label("  João\u00a0Pedro\t dos Santos  "));
        assertEquals("Álvaro Ç.",ParticipantIdentity.label("Álvaro çedilha"));
        assertEquals("Ana-Maria S.",ParticipantIdentity.label("Ana-Maria Souza"));
        assertEquals("Cher",ParticipantIdentity.label("Cher"));
    }
    @Test void deletedOrMissingProfileDoesNotInventAFormerIdentity() {
        assertEquals("Conta excluída",ParticipantIdentity.label(null));
        assertEquals("Conta excluída",ParticipantIdentity.label("Conta excluída"));
        assertEquals("Participante",ParticipantIdentity.label("\t "));
    }
    @Test void displayLengthIsBoundedWithoutSplittingASupplementaryCharacter() {
        String label=ParticipantIdentity.label("𐐀".repeat(40)+" Silva");
        assertEquals("𐐀".repeat(32)+" S.",label);
        assertFalse(label.contains("Silva"));
    }
}
