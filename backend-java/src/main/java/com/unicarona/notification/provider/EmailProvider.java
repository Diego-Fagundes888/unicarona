package com.unicarona.notification.provider;

import java.util.UUID;

public interface EmailProvider {
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    record Message(UUID id,String to,String kind,String url,String name,com.unicarona.notification.template.AuthEmailTemplate.Content content) {
        public Message(UUID id,String to,String kind,String url){this(id,to,kind,url,"",null);}
        public Message(UUID id,String to,String kind,String url,String name){this(id,to,kind,url,name,null);}
        @Override public String toString() { return "EmailMessage[redacted]"; }
    }
    void send(Message input) throws Exception;
}
