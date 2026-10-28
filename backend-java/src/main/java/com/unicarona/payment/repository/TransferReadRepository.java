package com.unicarona.payment.repository;

import com.unicarona.payment.dto.TransferDto;
import com.unicarona.shared.ApiDates;
import com.unicarona.shared.ApiException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransferReadRepository {
    private final JdbcTemplate sql;
    public TransferReadRepository(JdbcTemplate sql) { this.sql=sql; }
    public UUID owner(UUID id) {
        var owners=sql.queryForList("SELECT user_id FROM transfers WHERE id=?",UUID.class,id);
        ApiException.require(!owners.isEmpty(),"TRANSFER_NOT_FOUND","Transferência não encontrada.",404);return owners.getFirst();
    }
    public Optional<String> cadence(UUID owner) {
        return sql.queryForList("SELECT withdrawal_cadence FROM student_profiles WHERE user_id=?",String.class,owner).stream().findFirst();
    }
    public List<TransferDto.Item> list(UUID owner,int limit,int offset) {
        return sql.query("SELECT id,type,cents,status,cadence,created_at,updated_at FROM transfers WHERE user_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
            (r,n) -> new TransferDto.Item(r.getObject("id",UUID.class),r.getString("type"),r.getInt("cents"),r.getString("status"),r.getString("cadence"),ApiDates.timestamp(r.getTimestamp("created_at").toInstant()),ApiDates.timestamp(r.getTimestamp("updated_at").toInstant())),owner,limit,offset);
    }
}
