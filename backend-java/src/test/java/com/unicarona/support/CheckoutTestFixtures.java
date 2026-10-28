package com.unicarona.support;

import com.unicarona.payment.repository.PaymentReadRepository;
import java.util.*;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit current-trip review precondition for older, unrelated financial/lifecycle fixtures.
 * The review-specific HTTP cases use raw requests instead, including absent and stale reviews. */
public final class CheckoutTestFixtures {
    private CheckoutTestFixtures(){}
    public static Object reviewedBody(ApplicationContext app,String method,String path,Object body){
        if(!"POST".equals(method)||!path.matches("/api/v1/payments/[a-fA-F0-9-]{36}/checkout")||!(body instanceof Map<?,?> input)
            ||input.containsKey("tripReview")||!(input.get("method") instanceof String)||!(input.get("useCredit") instanceof Boolean))return body;
        var sql=app.getBean(JdbcTemplate.class);
        if(!"unicarona_java_test".equals(sql.queryForObject("SELECT current_database()",String.class)))throw new IllegalStateException("Only disposable Java fixtures may assume a trip review.");
        UUID id=UUID.fromString(path.split("/")[4]);
        var owners=sql.query("SELECT passenger_id FROM payments WHERE id=?",(r,n)->r.getObject(1,UUID.class),id);
        if(owners.isEmpty())return body;
        String review=app.getBean(PaymentReadRepository.class).summary(id,owners.getFirst()).tripReview();
        if(review==null)return body;
        var result=new LinkedHashMap<String,Object>();input.forEach((key,value)->result.put((String)key,value));result.put("tripReview",review);return result;
    }
}
