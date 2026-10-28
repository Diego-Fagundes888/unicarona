package com.unicarona.student.repository;

import com.unicarona.student.dto.IdentityDto;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AcademicQueueRepository {
    private final JdbcTemplate sql;
    public AcademicQueueRepository(JdbcTemplate sql) { this.sql=sql; }
    public List<IdentityDto.QueueItem> pending(int limit,int offset) {
        return sql.query("""
            SELECT u.id,u.name,un.name AS university,c.name AS course,sp.period_number,cl.label
            FROM student_profiles sp JOIN users u ON u.id=sp.user_id
            JOIN universities un ON un.id=sp.university_id JOIN courses c ON c.id=sp.course_id
            LEFT JOIN classes cl ON cl.id=sp.class_id WHERE sp.verification_status='pending'
            ORDER BY sp.updated_at,u.id LIMIT ? OFFSET ?
            """,(r,n) -> new IdentityDto.QueueItem(r.getObject("id",UUID.class),r.getString("name"),r.getString("university"),r.getString("course"),r.getObject("period_number",Integer.class),r.getString("label")),limit,offset);
    }
}
