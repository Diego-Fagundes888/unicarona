package com.unicarona.privacy.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Public controller identity and bounded policy values; never invent a business identity. */
@Component
public final class PrivacySettings {
    private final String controllerName,contactEmail,city,state,providerIdentification,providerAddress;
    private final boolean commercialOperation;
    private final int deletionGraceDays,registrationRestrictionDays,backupRetentionDays;
    public PrivacySettings(Environment env){
        controllerName=value(env,"LEGAL_CONTROLLER_NAME");contactEmail=value(env,"LEGAL_PRIVACY_EMAIL");
        city=value(env,"LEGAL_CITY");state=value(env,"LEGAL_STATE");
        providerIdentification=value(env,"LEGAL_PROVIDER_IDENTIFICATION");providerAddress=value(env,"LEGAL_PROVIDER_ADDRESS");
        String commercial=env.getProperty("LEGAL_COMMERCIAL_OPERATION","false");
        if(!java.util.Set.of("true","false").contains(commercial))throw new IllegalStateException("Invalid legal operating configuration.");
        commercialOperation="true".equals(commercial);
        deletionGraceDays=bounded(env,"PRIVACY_DELETION_GRACE_DAYS",7,1,14);
        registrationRestrictionDays=bounded(env,"PRIVACY_REGISTRATION_RESTRICTION_DAYS",365,1,365);
        backupRetentionDays=bounded(env,"PRIVACY_BACKUP_RETENTION_DAYS",35,1,90);
        if(!contactEmail.isEmpty()&&(contactEmail.length()>254||!contactEmail.matches(com.unicarona.auth.dto.AuthDto.EMAIL_PATTERN)))throw new IllegalStateException("Invalid privacy contact configuration.");
        if(!state.isEmpty()&&!state.matches("[A-Z]{2}"))throw new IllegalStateException("Invalid controller location configuration.");
        if("production".equals(env.getProperty("NODE_ENV"))){
            if(controllerName.length()<2||contactEmail.isEmpty()||city.isEmpty()||state.isEmpty())throw new IllegalStateException("Production requires an identified controller and privacy contact.");
            if(commercialOperation&&(providerIdentification.isEmpty()||providerAddress.isEmpty()))throw new IllegalStateException("Commercial operation requires configured supplier identification and address.");
        }
    }
    private static String value(Environment env,String key){String s=env.getProperty(key,"").strip();if(s.length()>500||s.contains("\r")||s.contains("\n"))throw new IllegalStateException("Invalid public legal configuration.");return s;}
    private static int bounded(Environment env,String key,int fallback,int min,int max){int value=env.getProperty(key,Integer.class,fallback);if(value<min||value>max)throw new IllegalStateException("Invalid bounded privacy policy configuration.");return value;}
    public String controllerName(){return controllerName;}
    public String contactEmail(){return contactEmail;}
    public String city(){return city;}
    public String state(){return state;}
    public String providerIdentification(){return providerIdentification;}
    public String providerAddress(){return providerAddress;}
    public boolean commercialOperation(){return commercialOperation;}
    public int deletionGraceDays(){return deletionGraceDays;}
    public int registrationRestrictionDays(){return registrationRestrictionDays;}
    public int backupRetentionDays(){return backupRetentionDays;}
    @Override public String toString(){return "PrivacySettings[redacted]";}
}
