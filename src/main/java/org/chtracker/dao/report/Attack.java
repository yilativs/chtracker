package org.chtracker.dao.report;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;

import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.ForeignKey;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import org.chtracker.dao.DataConfiguration;
import org.chtracker.dao.profile.Patient;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(schema = DataConfiguration.REPORT_SCHEMA_NAME, uniqueConstraints = { @UniqueConstraint(name = "attack_uniq", columnNames = { "patient_id", "started" }) }, indexes = {
        @Index(name = "attack__patient_id_started_idx", columnList = "patient_id, started") })
public class Attack {

    @Id
    @GeneratedValue(generator = "attackSequenceGenerator", strategy = GenerationType.SEQUENCE)
    @GenericGenerator(name = "attackSequenceGenerator", strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator", parameters = {
            @Parameter(name = "sequence_name", value = "report.attack_seq"),
            @Parameter(name = "initial_value", value = "1"),
            @Parameter(name = "increment_size", value = "1")
    })
    private int id;

    @NotNull
    private LocalDateTime started;
    private LocalDateTime stopped;

    @JsonIgnore
    @ManyToOne(optional = false)
    @JoinColumn(foreignKey = @ForeignKey(name = "attack__patient_fk"))
    private Patient patient;
    @NotNull
    @Min(1)
    @Max(10)
    private byte maxPainLevel;

    private Boolean whileAsleep;
    @Size(max = 1000)
    private String comments;

    @OneToMany(mappedBy = "attack", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Collection<AbortiveTreatment> abortiveTreatments = new ArrayList<>();

    Attack() {
        // needed for Hibernate (we can use private, but it will trigger Unused
        // constructor warning
    }

    public Attack(LocalDateTime started, LocalDateTime stopped, Patient patient, byte maxPainLevel, Boolean whileAsleep, String comments) {
        this.started = started;
        this.stopped = stopped;
        this.patient = patient;
        this.maxPainLevel = maxPainLevel;
        this.whileAsleep = whileAsleep;
        this.comments = comments;
    }

    public void addAbortiveTreatment(AbortiveTreatment abortiveTreatment) {
        abortiveTreatments.add(abortiveTreatment);
        abortiveTreatment.setAttack(this);
    }

    public void removeAbortiveTreatment(AbortiveTreatment abortiveTreatment) {
        abortiveTreatments.remove(abortiveTreatment);
        abortiveTreatment.setAttack(null);
    }

    public Collection<AbortiveTreatment> getAbortiveTreatments() {
        return abortiveTreatments;
    }

    public LocalDateTime getStarted() {
        return started;
    }

    public void setStarted(LocalDateTime started) {
        this.started = started;
    }

    public LocalDateTime getStopped() {
        return stopped;
    }

    public void setStopped(LocalDateTime stopped) {
        this.stopped = stopped;
    }

    public int getMaxPainLevel() {
        return maxPainLevel;
    }

    public void setMaxPainLevel(byte maxPainLevel) {
        this.maxPainLevel = maxPainLevel;
    }

    public Boolean isWhileAsleep() {
        return whileAsleep;
    }

    public void setWhileAsleep(Boolean whileAsleep) {
        this.whileAsleep = whileAsleep;
    }

    public String getComments() {
        return comments;
    }

    public void setComments(String comments) {
        this.comments = comments;
    }

    public Patient getPatient() {
        return patient;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((patient == null) ? 0 : patient.hashCode());
        result = prime * result + ((started == null) ? 0 : started.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Attack other = (Attack) obj;
        if (!patient.equals(other.patient))
            return false;
        return started.equals(other.started);
    }

    @Override
    public String toString() {
        return "Attack [patient=" + patient + ", started=" + started + "]";
    }

}
