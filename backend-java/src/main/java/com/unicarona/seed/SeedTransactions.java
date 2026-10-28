package com.unicarona.seed;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.auth.repository.AuthRepository;
import com.unicarona.file.storage.PrivateFileStorage;
import com.unicarona.shared.ApiException;
import java.io.IOException;
import java.security.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

@Service
public class SeedTransactions {
    public record Seeded(UUID university,Map<String,UUID> users) { public Seeded { users=Map.copyOf(users); } }
    private record Person(String email,String name,String role) {}
    private static final byte[] DOCUMENT=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jB8sAAAAASUVORK5CYII=");
    private final JdbcTemplate sql;
    private final AuthRepository academic;
    private final PrivateFileStorage files;
    private final SeedSettings settings;
    public SeedTransactions(JdbcTemplate sql,AuthRepository academic,PrivateFileStorage files,SeedSettings settings) { this.sql=sql;this.academic=academic;this.files=files;this.settings=settings; }
    @Transactional public Seeded seed(SeedCatalog.Catalog catalog,String hash) {
        settings.allowed();sql.execute("SET LOCAL lock_timeout='3s'");sql.execute("SELECT pg_advisory_xact_lock(hashtext('unicarona:development:seed'))");
        UUID university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code,latitude,longitude) VALUES('uniceplac','UNICEPLAC','Gama','DF',-16.0030499,-48.0518692) ON CONFLICT(slug) DO UPDATE SET name=excluded.name RETURNING id",UUID.class);
        for(var course:catalog.courses()) sql.update("INSERT INTO courses(university_id,slug,name) VALUES(?,?,?) ON CONFLICT(university_id,slug) DO UPDATE SET name=excluded.name",university,course.id(),course.label());
        for(var region:catalog.regions()) sql.update("INSERT INTO regions(slug,name,state_code) VALUES(?,?,?) ON CONFLICT(slug) DO UPDATE SET name=excluded.name",region.id(),region.name(),region.state());
        UUID course=course(university,"analise-desenvolvimento-sistemas"),medicine=course(university,"medicina");
        var users=new LinkedHashMap<String,UUID>();
        for(var person:List.of(new Person("ana@example.com","Ana Souza","user"),new Person("carolina@example.com","Carolina Martins","user"),new Person("lucas@example.com","Lucas Almeida","user"),new Person("admin@example.com","Administração local","admin"))) {
            sql.update("INSERT INTO users(email,name,role,birth_date,password_hash,email_verified_at) VALUES(?,?,?,'2000-05-01',?,now()) ON CONFLICT(email) DO NOTHING",person.email(),person.name(),person.role(),hash);
            UUID id=sql.queryForObject("SELECT id FROM users WHERE email=?",UUID.class,person.email());users.put(person.email(),id);
            if("user".equals(person.role()) && !exists("SELECT EXISTS(SELECT 1 FROM student_profiles WHERE user_id=?)",id)) {
                boolean lucas=person.email().startsWith("lucas");academic.saveAcademic(id,new AuthDto.Academic(university.toString(),(lucas?medicine:course).toString(),null,"Lago Azul","numbered",lucas?5:3,"night",lucas?"B":"A"));
            }
        }
        if(!exists("SELECT EXISTS(SELECT 1 FROM pricing_versions WHERE university_id=?)",university)) sql.update("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps,created_by) VALUES(?,600,10,20,2,1000,1000,?)",university,users.get("admin@example.com"));
        var created=new ArrayList<String>();TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { if(status==STATUS_ROLLED_BACK) created.forEach(files::remove); }
        });
        driver(users.get("carolina@example.com"),"Volkswagen Polo","Prata","UNI1A23",users.get("admin@example.com"),created);
        driver(users.get("lucas@example.com"),"Chevrolet Onix","Branco","UNI2B34",users.get("admin@example.com"),created);
        return new Seeded(university,users);
    }
    private UUID course(UUID university,String slug) { return sql.queryForObject("SELECT id FROM courses WHERE university_id=? AND slug=?",UUID.class,university,slug); }
    private boolean exists(String query,Object... args) { return Boolean.TRUE.equals(sql.queryForObject(query,Boolean.class,args)); }
    private void driver(UUID user,String model,String color,String plate,UUID admin,List<String> created) {
        if(exists("SELECT EXISTS(SELECT 1 FROM driver_profiles WHERE user_id=?)",user)) return;
        sql.update("INSERT INTO driver_profiles(user_id,status,approved_at) VALUES(?,'approved',now())",user);
        UUID vehicle=sql.queryForObject("INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,?,?,?,3,'approved') RETURNING id",UUID.class,user,model,color,plate);
        var documents=new ArrayList<UUID>();
        for(String purpose:List.of("identity","license")) {
            UUID id=UUID.randomUUID();created.add(id.toString());
            try { files.put(id.toString(),DOCUMENT); }catch(IOException error) { throw new ApiException("SEED_STORAGE_FAILED","Não foi possível armazenar os documentos sintéticos.",500); }
            sql.update("INSERT INTO stored_files(id,owner_id,purpose,storage_key,content_type,byte_size,sha256) VALUES(?,?,?,?,'image/png',?,?)",id,user,purpose,id.toString(),DOCUMENT.length,digest());documents.add(id);
        }
        sql.update("INSERT INTO driver_applications(user_id,vehicle_id,identity_file_id,license_file_id,status,decision,reviewed_by,reviewed_at) VALUES(?,?,?,?,'approved','Dados sintéticos de desenvolvimento',?,now())",user,vehicle,documents.get(0),documents.get(1),admin);
    }
    private String digest() { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(DOCUMENT)); }catch(NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable."); } }
}
