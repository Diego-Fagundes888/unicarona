package com.unicarona.student.service;

import com.unicarona.notification.service.NotificationService;
import com.unicarona.security.Actor;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.repository.ActorsRepository;
import com.unicarona.shared.repository.AuditRepository;
import com.unicarona.student.dto.IdentityDto;
import com.unicarona.student.repository.StudentProfileRepository;
import com.unicarona.user.dto.ProfileDto;
import com.unicarona.user.repository.ProfileRepository;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityTransactions {
    private final ActorsRepository actors;
    private final StudentProfileRepository students;
    private final ProfileRepository profiles;
    private final AuditRepository audit;
    private final NotificationService notifications;
    public IdentityTransactions(ActorsRepository actors,StudentProfileRepository students,ProfileRepository profiles,AuditRepository audit,NotificationService notifications) {
        this.actors=actors;this.students=students;this.profiles=profiles;this.audit=audit;this.notifications=notifications;
    }
    @Transactional public IdentityDto.Settings settings(Actor actor,IdentityDto.Settings input) {
        actors.lockUser(actor.id());
        ApiException.require(students.updateSettings(actor.id(),input.notices(),input.withdrawal())==1,"PROFILE_UNAVAILABLE","Perfil não encontrado.",404);
        audit.record(actor.id(),"settings_updated","user",actor.id(),Map.of()); return input;
    }
    @Transactional public IdentityDto.Verification applyVerification(Actor actor,ProfileDto expected,IdentityDto.Verification result) {
        actors.lockUser(actor.id()); var current=profiles.find(actor.id());
        ApiException.require(Objects.equals(current.classId(),expected.classId()) && Objects.equals(current.courseId(),expected.courseId())
            && Objects.equals(current.universityId(),expected.universityId()),"PROFILE_CHANGED","O perfil mudou. Solicite novamente.",409);
        students.updateVerification(actor.id(),result.status(),result.source());
        audit.record(actor.id(),"academic_verification_requested","user",actor.id(),Map.of("status",result.status(),"source",result.source())); return result;
    }
    @Transactional public IdentityDto.Decision review(Actor actor,UUID user,IdentityDto.Review input) {
        ApiException.require("admin".equals(actor.role()),"FORBIDDEN","Acesso administrativo necessário.",403);
        actors.lockUser(user);
        var profile=students.lockProfile(user);
        ApiException.require(profile.isPresent() && "pending".equals(profile.get().getVerificationStatus()),"VERIFICATION_STATE","Perfil não aguarda análise.",409);
        String status=input.approve()?"verified":"rejected"; students.updateVerification(user,status,"administrative");
        audit.record(actor.id(),"academic_verification_reviewed","user",user,Map.of("approved",input.approve(),"reason",input.reason()));
        notifications.notify(user,"Vínculo acadêmico analisado",input.reason(),"perfil",user);
        return new IdentityDto.Decision(user,status,"administrative");
    }
}
