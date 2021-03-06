package org.chtracker.dao.loader;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Integer.parseInt;
import static java.time.LocalDateTime.parse;
import static org.apache.commons.csv.CSVFormat.DEFAULT;
import static org.chtracker.dao.loader.YilativsCvsDataLoader.CsvColumn.ABORTION_TREATMENT;
import static org.chtracker.dao.loader.YilativsCvsDataLoader.CsvColumn.COMMENTS;
import static org.chtracker.dao.loader.YilativsCvsDataLoader.CsvColumn.DATE;
import static org.chtracker.dao.loader.YilativsCvsDataLoader.CsvColumn.DESCRIPTION;
import static org.chtracker.dao.loader.YilativsCvsDataLoader.CsvColumn.LASTED;
import static org.chtracker.dao.loader.YilativsCvsDataLoader.CsvColumn.LEVEL;
import static org.chtracker.dao.loader.YilativsCvsDataLoader.CsvColumn.TIME;
import static org.chtracker.dao.loader.YilativsCvsDataLoader.CsvColumn.TREATMNT_STATUS;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.apache.commons.csv.CSVRecord;
import org.chtracker.dao.metadata.TreatmentType;
import org.chtracker.dao.metadata.TreatmentTypeRepository;
import org.chtracker.dao.profile.Patient;
import org.chtracker.dao.profile.PatientRepository;
import org.chtracker.dao.report.AbortiveTreatment;
import org.chtracker.dao.report.Attack;
import org.chtracker.dao.report.PreventiveTreatment;
import org.chtracker.dao.report.ReportRepository;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnNotWebApplication
public class YilativsCvsDataLoader extends AbstractLoader {

	@Value("${attacks.yilativs.path:data/yilativs-attacks.tsv}")
	String attacksDataPath;

	static final DateTimeFormatter dateTimeFormatterWithSeconds = DateTimeFormatter.ofPattern("M/d/uuuu H:mm:ss");
	static final DateTimeFormatter dateTimeFormatterWithoutSeconds = DateTimeFormatter.ofPattern("M/d/uuuu H:mm");

	enum CsvColumn {
		DATE, TIME, LASTED, LEVEL, DESCRIPTION, ABORTION_TREATMENT, TREATMNT_STATUS, LEFT_SHADOWS, PREVENTIVE_TREATMENT, COMMENTS
	}

	final TreatmentTypeRepository treatmentRepository;

	final PatientRepository patientRepository;

	final Patient patient;

	final ReportRepository reportRepository;

	private Logger logger;

	public YilativsCvsDataLoader(TreatmentTypeRepository treatmentRepository, PatientRepository patientRepository, ReportRepository reportRepository, Logger logger) {
		this.treatmentRepository = treatmentRepository;
		this.patientRepository = patientRepository;
		this.logger = logger;
		patient = this.patientRepository.findByLogin("yilativs");
		this.reportRepository = reportRepository;
	}

	@Transactional
	public void load() throws FileNotFoundException, IOException, ParseException {
		if (attacksDataPath == null) {
			throw new IllegalStateException("Attacks data can not be loaded: attacks.yilativs.path is not specified");
		}
		if (reportRepository.getAttackCount(patient)>0) {
			logger.warn("Data from Yilativs were already loaded");
			return;
		}
		try (Reader reader = new FileReader(attacksDataPath)) {
			for (CSVRecord record : DEFAULT.withDelimiter('\t').parse(reader)) {
				try {
					String date = record.get(DATE.ordinal());
					if (date == null || date.length() < 5 || !date.matches("\\d.*")) {
						continue;
					}
					String dateTimeString = record.get(DATE.ordinal()) + " " + record.get(TIME.ordinal());

					LocalDateTime startDateTime = dateTimeString.split(":").length > 2 ? parse(dateTimeString, dateTimeFormatterWithSeconds) : parse(dateTimeString, dateTimeFormatterWithoutSeconds);
					int lasted = parseInt(record.get(LASTED.ordinal()));
					int level = 2 * parseInt(record.get(LEVEL.ordinal()));

					String comments = getComments(record);
					reportRepository.save(new Attack(startDateTime, startDateTime.plusMinutes(lasted), patient, level, null, comments));
					saveAbortiveTreatments(record.get(ABORTION_TREATMENT.ordinal()), startDateTime, startDateTime.plusMinutes(lasted), record.get(TREATMNT_STATUS.ordinal()), comments);
					savePreventiveTreatments(record.get(CsvColumn.PREVENTIVE_TREATMENT.ordinal()), startDateTime);
				} catch (Exception e) {
					logger.error("failed to handle record" + record + " " + e.getClass().getName() + " " + e.getMessage(),e);
				}
			}
		}
	}

	private String getComments(CSVRecord record) {
		String painDescription = record.get(DESCRIPTION.ordinal());
		String comments = record.get(COMMENTS.ordinal());
		if (StringUtils.hasText(comments)) {
			if (StringUtils.hasText(painDescription)) {
				comments = painDescription + "; " + comments;
			}
		} else {
			comments = painDescription;
		}
		return comments;
	}

	private void savePreventiveTreatments(String preventiveTreatmentString, LocalDateTime startDateTime) {
		if (StringUtils.hasText(preventiveTreatmentString)) {
			String[] treatmentStrings = preventiveTreatmentString.split("\\+|,");
			for (String treatmentString : treatmentStrings) {
				try {
					TreatmentType treatmentType = treatmentRepository.findByNameContainingIgnoreCase(treatmentString.split(" \\d")[0].trim());
					if (treatmentType == null)
						continue;
					int doze = getDoze(treatmentType, treatmentString);
					LocalDate startDate = startDateTime.toLocalDate();
					Optional<LocalDateTime> previousUsageDateOptional = reportRepository.getPreviousPreventiveTreatmentUsageStart(treatmentType, startDateTime, patient);
					if (previousUsageDateOptional.isEmpty()) {
						reportRepository.save(new PreventiveTreatment(startDateTime, patient, treatmentType, doze, preventiveTreatmentString));
					} else {
						LocalDateTime previousUsageDate = previousUsageDateOptional.get();
						if (previousUsageDate.toLocalDate().equals(startDate)) {
							continue;// because treatment already registered
						} else {
							reportRepository.save(new PreventiveTreatment(startDateTime, patient, treatmentType, doze, preventiveTreatmentString));
							long days = ChronoUnit.DAYS.between(previousUsageDate.toLocalDate(), startDate);
							if (days < 30) {
								int i = 1;
								while (previousUsageDate.plusDays(i).toLocalDate().compareTo(startDate) < 0) {
									reportRepository.save(new PreventiveTreatment(previousUsageDate.plusDays(i++), patient, treatmentType, doze, preventiveTreatmentString));
								}
							}

						}
					}
				} catch (RuntimeException e) {
					logger.error(preventiveTreatmentString + " " + treatmentString, e);
				}
			}
		}
	}

	static int getDoze(TreatmentType treatmentType, String doze) {
		switch (treatmentType.getName()) {
		case "100% oxygen via nonrebreathing mask":
			return DEFUALT_O2_LPM;
		case "Vitamin D3 pills":
		case "Vitamin D3 sun exposure":
		case "Vitamin D3 UVB lamp":
			return DEFAULT_VITAMIN_D3_DOZE_UI;
		case "Sumatriptan pills":
			return Integer.parseInt(doze.toLowerCase().replaceAll("[a-z]", "").trim());
		case "Sumatriptan nasal spray":
			return Integer.parseInt(doze.toLowerCase().replaceAll("[a-z]", "").trim());
		case "Verapamil":
			return Integer.parseInt(doze.toLowerCase().replaceAll("[a-z]", "").trim());
		case "Caffeine in a drink e.g. coffee, redbull or pepsi":
			return DEFAULT_COFFIEINE_DRINK_MG;
		case "Psilocybin mushroom":
			return Integer.parseInt(doze.toLowerCase().replaceAll("[a-z]", "").trim());
		case "Lidocaine drops 4%":
			return 4;
		case "Cardio Workout":
		case "Hyperventilation":
			return DEFAULT_BPM;
		default:
			throw new IllegalArgumentException(doze + " doze is incorrect for type " + treatmentType);
		}
	}

	void saveAbortiveTreatments(String treatmentsString, LocalDateTime started, LocalDateTime stopped, String statusesString, String comments) {
		if (StringUtils.hasText(treatmentsString)) {
			String[] treatmentStrings = treatmentsString.split("\\+|,");
			String[] statusStrings = statusesString.split("/");
			Boolean[] statuses = new Boolean[statusStrings.length];
			for (int i = 0; i < statusStrings.length; i++) {
				statuses[i] = StringUtils.hasText(statusStrings[i]) ? parseBoolean(statusStrings[i]) : null;
			}
			Boolean status = null;
			int i = 0;
			for (String treatmentString : treatmentStrings) {
				status = i < statuses.length ? statuses[i] : status;
				i++;
				try {
					if (treatmentsString.trim().length() == 0) {
						continue;
					}
					String treatmentName = treatmentString.split("\\d")[0].trim();

					TreatmentType treatmentType = treatmentName.toLowerCase().contains("oxygen") ? treatmentRepository.findByNameContainingIgnoreCase("100% oxygen via nonrebreathing mask")
							: treatmentRepository.findByNameContainingIgnoreCase(treatmentName);
					if (treatmentType == null)
						continue;
					int doze = getDoze(treatmentType, treatmentString);
					if (treatmentType.getName().equals("100% oxygen via nonrebreathing mask") || treatmentType.getName().equals("Cardio Workout")
							|| treatmentType.getName().equals("Hyperventilation")) {
						reportRepository.save(new AbortiveTreatment(started, started, stopped, patient, treatmentType, doze, status, comments));
					} else {
						reportRepository.save(new AbortiveTreatment(started, started, null, patient, treatmentType, doze, status, comments));
					}
				} catch (RuntimeException e) {
					logger.error("failed to save treatment string" + treatmentsString, e);
				}
			}
		}

	}

}
