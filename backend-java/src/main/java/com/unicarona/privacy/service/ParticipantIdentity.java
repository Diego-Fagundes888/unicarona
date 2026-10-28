package com.unicarona.privacy.service;

/** Display minimization, not anonymization: contract identifiers and restricted source records remain. */
public final class ParticipantIdentity {
    private ParticipantIdentity() {}
    public static String label(String name) {
        if(name==null || name.equals("Conta excluída")) return "Conta excluída";
        String clean=name.replaceAll("(?U)\\s+"," ").strip();
        if(clean.isEmpty()) return "Participante";
        String[] parts=clean.split(" ");String first=parts[0];
        if(first.codePointCount(0,first.length())>32)first=first.substring(0,first.offsetByCodePoints(0,32));
        if(parts.length==1)return first;
        String last=parts[parts.length-1];String initial=new String(Character.toChars(last.codePointAt(0))).toUpperCase(java.util.Locale.ROOT);
        return first+" "+initial+".";
    }
}
