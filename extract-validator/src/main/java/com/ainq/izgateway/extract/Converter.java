package com.ainq.izgateway.extract;
/*
 * Copyright 2020 Audiacious Inquiry, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

import com.ainq.izgateway.extract.annotations.EventType;
import com.ainq.izgateway.extract.annotations.FieldValidator;
import com.ainq.izgateway.extract.annotations.Requirement;
import com.ainq.izgateway.extract.annotations.RequirementType;
import com.ainq.izgateway.extract.annotations.V2Field;
import com.ainq.izgateway.extract.validation.BeanValidator;
import com.ainq.izgateway.extract.validation.CVRSEntry;
import com.ainq.izgateway.extract.validation.DateValidator;
import com.ainq.izgateway.extract.validation.DateValidatorIfKnown;
import com.ainq.izgateway.extract.validation.ValueSetValidator;
import com.opencsv.exceptions.CsvException;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Composite;
import ca.uhn.hl7v2.model.DataTypeException;
import ca.uhn.hl7v2.model.Group;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.Primitive;
import ca.uhn.hl7v2.model.Segment;
import ca.uhn.hl7v2.model.Structure;
import ca.uhn.hl7v2.model.Type;
import ca.uhn.hl7v2.model.Varies;
import ca.uhn.hl7v2.model.v251.datatype.CE;
import ca.uhn.hl7v2.model.v251.message.VXU_V04;
import ca.uhn.hl7v2.model.v251.segment.OBX;
import ca.uhn.hl7v2.util.Terser;

/**
 * Converter is a utility class to convert between HL7 Version 2 Messages and CVRSExtract files.
 * It is used by Validator to support file conversion operations.
 *
 * @author Keith W. Boone
 */
public class Converter {
    /**
     * Private constructor.  This class only contains static methods.
     */
    private Converter() {
    }

    /**
     * The order of segments in a VXU Message.
     */
    public final static String[] SEGMENT_ORDER = { "MSH", "PID", "ORC", "RXA", "RXR", "OBX" };

    /**
     * Given an HL7 Message, and a validator, convert the message to a CVRSExtract,
     * applying default conversion rules.
     *
     * @param message   The message to convert.
     * @param exList    A place to store any exceptions generated by the Validator.
     * @param validator The validator to use for validating outputs, may be null.
     * @param line      The current message number being converted.
     * @return  The converted CVRSExtract
     */
    public static CVRSExtract fromHL7(Message message, List<CVRSEntry> exList, BeanValidator validator, int line) {
        return fromHL7(message, exList, validator, true, line);
    }

    /**
     * Given an HL7 Message, and a validator, convert the message to a CVRSExtract.
     * @param message   The message to convert.
     * @param exList    A place to store any exceptions generated by the Validator.
     * @param validator The validator to use for validating outputs, may be null.
     * @param line      The current message number being converted.
     * @param useDefaults   If true, set
     * @return  The converted CVRSExtract
     */
    public static CVRSExtract fromHL7(Message message, List<CVRSEntry> exList, BeanValidator validator, boolean useDefaults, int line)  {
        Terser terser = new Terser(message);
        CVRSExtract extract = new CVRSExtract();
        String version = validator == null ? Validator.DEFAULT_VERSION : validator.getVersion();

        extract.forEachField(true, field -> {
            // Don't process a field if it should be ignored for this version
            if (BeanValidator.getRequirement(field, RequirementType.IGNORE, version) != null) {
                return;
            }

            // Get the annotations used to guide conversion
            field.setAccessible(true);
            V2Field v2Data = field.getAnnotation(V2Field.class);
            FieldValidator val = field.getAnnotation(FieldValidator.class);
            // If there's no V2Field annotation, we don't know how to convert the field.
            // For now, this is an error.
            if (v2Data == null) {
                throw new RuntimeException(field.getName() + " is not annotated.");
            }
            String value = null;

            try {
                if (v2Data.obx3() != null && v2Data.obx3().length() != 0) {
                    value = setupObx3(message, v2Data, val);
                } else {
                    String content = terser.get("/." + v2Data.value());
                    if (StringUtils.isEmpty(content) && v2Data.alternate() != null && v2Data.alternate().length() != 0) {
                        content = terser.get("/." + v2Data.alternate());
                    }
                    value = adjustValuesToExtract(content, val, v2Data.map());
                }
            } catch (HL7Exception e) {
                throw new RuntimeException("Unexpected HL7Exception", e);
            }
            field.set(extract, value);
        });

        if (useDefaults) {
            setDefaultValues(extract, version);
        }

        if (exList != null) {
            // Add data to line values in Error entries.
            String values[] = extract.getValues();
            for (CVRSEntry ex: exList) {
                ex.setRow(values);
                ex.setLine(line);
            }
        }
        return extract;
    }

    /**
     * Find the Obx3 Value to adjust or create a new one for the field.
     * @param message   The message to obtain content from
     * @param v2Data    The V2Field describing V2 Conversion
     * @param val       The FieldValidator providing data type information.
     * @return          The value to set in the Obx3
     * @throws HL7Exception If the value could not be set due to an HL7 Parsing error.
     */
    private static String setupObx3(Message message, V2Field v2Data, FieldValidator val) throws HL7Exception {
        String value;
        // Special handling to find the right OBX
        List<Segment> obxList = getAllOrderObxSegments(message);
        value = "";
        for (Segment obx: obxList) {
            CE id;
            id = (CE) obx.getField(3, 0);
            String code = id.getIdentifier().getValue();
            String system = id.getNameOfCodingSystem().getValue();
            String obx3Parts[] = v2Data.obx3().split("\\^");
            if (obx3Parts.length > 0 && obx3Parts[0].equalsIgnoreCase(code)) {
                if (obx3Parts.length <= 2 || obx3Parts[2].equalsIgnoreCase(system)) {
                    value = adjustValuesToExtract(obx.getField(5, 0), val, v2Data.map());
                    break;
                }
            }
        }
        return value;
    }

    /**
     * Set default values for the given extract based on the version of the extract.
     * @param extract   The extract to adjust
     * @param ver  The version of the extract to work with, or null to use the default (most current)
     * version.
     */
    private static void setDefaultValues(CVRSExtract extract, String ver) {
        String version = ver == null ? Validator.DEFAULT_VERSION : ver;
        EventType eventType = BeanValidator.getEventType(extract);
        extract.forEachField(true, field -> {
            field.setAccessible(true);
            String value = (String) field.get(extract);
            FieldValidator val = field.getAnnotation(FieldValidator.class);

            try {
                Requirement dns = BeanValidator.getRequirement(field, RequirementType.DO_NOT_SEND, version);
                // If this is a Do Not Send case, clear it.
                if (dns != null && Arrays.asList(dns.when()).contains(eventType)) {
                    field.set(extract, null);
                    return;
                }

                // If it's not a value set check, skip it.
                if (val == null || !ValueSetValidator.class.isAssignableFrom(val.validator())) {
                    return;
                }

                ValueSetValidator v = (ValueSetValidator) val.validator().getConstructor().newInstance();
                v.setParameterString(val.paramString());

                // If the field is not empty, skip it.
                if (!StringUtils.isEmpty(value)) {
                    return;
                }
                // If UNK isn't a legal value skip it.
                if (!v.isValid("UNK")) {
                    return;
                }

                Requirement req = BeanValidator.getRequirement(field, RequirementType.REQUIRED, version);
                // If the field isn't required, or isn't required for the current event type then skip it.
                if (req == null || !Arrays.asList(req.when()).contains(eventType)) {
                    return;
                }

                // The field is required, isn't a Do Not Send Case, set to unknown.
                field.set(extract, "UNK");
            } catch (InstantiationException | InvocationTargetException | NoSuchMethodException
                | SecurityException e) {
                // At this point, we are certain this won't happen.
            }
        });

        extract.setExt_type(extract.isRedacted() ? "D" : "I");

        if (extract.getVax_refusal().equalsIgnoreCase("YES")) {
            if (StringUtils.isEmpty(extract.getRecip_middle_name())) {
                extract.setRecip_middle_name(" ");
            }
            if (StringUtils.isEmpty(extract.getRecip_address_street_2())) {
                extract.setRecip_address_street_2(" ");
            }
        }
    }

    /**
     * Convert an CVRS Extract to an HL7 Message
     * @param e The extract to convert
     * @param ver The version of the CVRS Extract specification to test (if null, use the current version).
     * @return  The converted HL7 Message
     * @throws HL7Exception If an exception occurs converting to HL7 (e.g., bad value for a data type)
     */
    public static VXU_V04 toHL7(CVRSExtract e, String ver) throws HL7Exception {
        String version = ver == null ? Validator.DEFAULT_VERSION : ver;
        VXU_V04 message = new VXU_V04();
        try {
            message.initQuickstart("VXU", "V04", "P");
        } catch (HL7Exception | IOException e2) {
            throw new RuntimeException("Cannot initialize VXU_V04", e2);
        }
        Field[] fields = e.getClass().getDeclaredFields();
        Arrays.sort(fields, Converter::compareFields);
        Terser terser = new Terser(message);

        e.forEachField(true, field -> {
            try {
                // Don't process a field if it should be ignored for this version
                if (version != null &&
                    BeanValidator.getRequirement(field, RequirementType.IGNORE, version) != null) {
                    return;
                }

                V2Field v2Data = field.getAnnotation(V2Field.class);
                FieldValidator val = field.getAnnotation(FieldValidator.class);
                if (v2Data == null) {
                    throw new RuntimeException(field.getName() + " is not annotated.");
                }

                field.setAccessible(true);
                String value = adjustValuesToHL7((String)field.get(e), val, v2Data.map());

                if (value != null && value.trim().length() != 0) {
                    boolean isObx = false;
                    if (v2Data.obx3() != null && v2Data.obx3().length() != 0) {
                        setObx3(message, v2Data);
                        isObx = true;
                    }
                    setValue(terser, v2Data, value, isObx);
                }
            } catch (HL7Exception e1) {
                System.err.printf("Unexpected HLException: %s%n", e1.getMessage());
            }
        });

        return message;
    }

    /**
     * Convert and encode a CVRS Extract to an HL7 Message String.
     * This method uses the HAPI V2 Library for HL7 V2 messaging.
     * @param e The extract to convert and encode
     * @param version The CVRS Version to use for extraction
     * @return  The converted HL7 Message as a String
     * @throws HL7Exception If an exception occurs converting to HL7 (e.g., bad value for a data type)
     */
    public static String toHL7String(CVRSExtract e, String version) throws HL7Exception {
        VXU_V04 message = toHL7(e, version);
        return message.encode();
    }

    /**
     * Adjust values provided in an HL7 Primitive value to values that should appear within the CVRSExtract.
     * @param value The value to convert.
     * @param val   The field validator used for the item to identify its data type.
     * @param map   A map of values used to convert between HL7 Message values and CVRS Extract value seets.
     * @return  The converted value.
     */
    private static String adjustValuesToExtract(String value, FieldValidator val, String[] map) {
        // Convert nulls to the empty string.
        if (value == null) {
            value = "";
        }
        if (val != null) {
            // Convert dates by inserting hypens between date parts.
            // Since CVRS does not use time values, that part of any date time is simply dropped.
            if (val.validator() == DateValidator.class || val.validator() == DateValidatorIfKnown.class) {
                StringBuffer b = new StringBuffer();
                if (value.length() > 0) {
                    b.append(StringUtils.substring(value, 0, 4));
                    if (value.length() > 4) {
                        b.append("-").append(StringUtils.substring(value, 4, 6));
                        if (value.length() > 6) {
                            b.append("-").append(StringUtils.substring(value, 6, 8));
                        }
                    }
                }
                return b.toString();
            }
        }
        // If there is no value set mapping, simply return the value.
        if (map == null || map.length == 0) {
            return value;
        }

        // Otherwise, walk the map to determine the appropriate value to report
        for (int i = 0; i < map.length; i += 2) {
            if (i == map.length - 1) {
                // final value in odd length maps is default to set if no mapping found.
                // we don't know HOW to unmap it.
                return null;
            }
            // Get just the first part of the value for comparison.
            if (value.equalsIgnoreCase(StringUtils.substringBefore(map[i+1],"^")) || map[i+1].length() == 0) {
                // If they match, return the mapped value.
                return map[i];
            }
        }
        // No match found, return empty string.
        return "";
    }

    /**
     * Adjust values provided in an HL7 Message component to values that should appear within the CVRSExtract.
     * This class drills down through components to find the primitive value to convert, and may call itself
     * recursively to obtain the primitive value to convert.
     *
     * @param field The value to convert.
     * @param val   The field validator used for the item to identify its data type.
     * @param map   A map of values used to convert between HL7 Message values and CVRS Extract value seets.
     * @return  The converted value.
     */
    private static String adjustValuesToExtract(Type field, FieldValidator val, String[] map) {
        if (field instanceof Primitive) {
            return adjustValuesToExtract(((Primitive) field).getValue(), val, map);
        } else if (field instanceof Varies) {
            return adjustValuesToExtract(((Varies) field).getData(), val, map);
        }
        try {
            Composite comp = (Composite) field;
            if (comp.getComponents().length == 0) {
                return adjustValuesToExtract("", val, map);
            }
            return adjustValuesToExtract(comp.getComponent(0), val, map);
        } catch (DataTypeException e) {
            // Won't happen.
            return null;
        }
    }


    /**
     * Adjust values provided in an CVRSExtract to values that should appear within the HL7 Message.
     * @param value The value to convert.
     * @param val   The field validator used for the item to identify its data type.
     * @param map   A map of values used to convert between HL7 Message values and CVRS Extract value seets.
     * @return  The converted value.
     */
    private static String adjustValuesToHL7(String value, FieldValidator val, String[] map) {
        if (val != null) {
            if (val.validator() == DateValidator.class || val.validator() == DateValidatorIfKnown.class) {
                return value.replace("-", "");
            }
        }
        if (map == null || map.length == 0) {
            return value;
        }
        for (int i = 0; i < map.length; i += 2) {
            if (i == map.length - 1) {
                // final value in odd length maps is default to set if no mapping found.
                return map[i];
            }
            if (map[i].equalsIgnoreCase(value)) {
                return map[i + 1].length() == 0 ? null : map[i+1];
            }
        }
        return null;
    }

    /**
     * Get all OBX Segments in the ORDER (following the ORC). NOTE: Other OBX might be present.
     * @param message   The message to extract segments from
     * @return  OBX Segments in the ORDER
     */
    private static List<Segment> getAllOrderObxSegments(Message message) {
        List<Segment> segments = new ArrayList<>();
        Structure[] orders;
        try {
            orders = message.getAll("ORDER");
            for (Structure order: orders) {
                Structure obses[] = ((Group)order).getAll("OBSERVATION");
                for (Structure obs: obses) {
                    Structure[] obx = ((Group)obs).getAll("OBX");
                    if (obx != null && obx.length > 0) {
                        segments.add((Segment) obx[0]);
                    }
                }
            }
            return segments;
        } catch (HL7Exception e) {
            throw new RuntimeException("HL7 Parser Error", e);
        }
    }

    /**
     * Computes the Segment index for sorting message components in order.
     * @param seg   The segment.
     * @return  The sort order to use for the segment.
     */
    private static int segmentIndex(String seg) {
        for (int i = 0; i < SEGMENT_ORDER.length; i++) {
            if (SEGMENT_ORDER[i].equals(seg)) {
                return i;
            }
        }
        return SEGMENT_ORDER.length;
    }

    /**
     * Set an OBX3 (Observation Code) for an Observation in the ORDER
     * @param message   The message to add an OBX3 to.
     * @param v2Data    The data about the V2 OBX to create.
     * @throws HL7Exception If an HL7 parsing error occured (unlikely)
     * @throws DataTypeException    If the values in obx3 are invalid (also unlikely).
     */
    private static void setObx3(VXU_V04 message, V2Field v2Data) throws HL7Exception, DataTypeException {
        OBX obx = message.getORDER().insertOBSERVATION(0).getOBX();
        obx.getValueType().setValue("CE");
        String obx3[] = v2Data.obx3().split("\\^");
        if (obx3.length > 0) {
            obx.getObservationIdentifier().getIdentifier().setValue(obx3[0]);
        }
        if (obx3.length > 1) {
            obx.getObservationIdentifier().getText().setValue(obx3[1]);
        }
        if (obx3.length > 2) {
            obx.getObservationIdentifier().getNameOfCodingSystem().setValue(obx3[2]);
        }
    }

    /**
     * Set a value in an HL7 Message
     * @param terser    The terser to use for value setting
     * @param v2Data    Data about what to set.
     * @param value     The value to set.
     * @param isObx     True if setting an value for an OBX Segment
     * @throws HL7Exception If an error occured setting the value.
     */
    private static void setValue(Terser terser, V2Field v2Data, String value, boolean isObx) throws HL7Exception {
        if (isObx && value.contains("^")) {
            String parts[] = value.split("\\^");
            for (int i = 0; i < parts.length; i++) {
                terser.set("/." + v2Data.value() + "-" + (i + 1), parts[i]);
            }
        } else {
            terser.set("/." + v2Data.value(), value);
        }
        if (v2Data.system().length() != 0) {
            String s = StringUtils.substringBeforeLast(v2Data.value(), "-")
                + "-" + (Integer.parseInt(StringUtils.substringAfterLast(v2Data.value(), "-")) + 2);
            terser.set("/." + s, v2Data.system());
        }
    }

    /**
     * comparator to sort Fields by position in an HL7 message.
     * @param o1    The first field.
     * @param o2    The second field.
     * @return  Which field should appear first in the message.
     */
    public static int compareFields(Field o1, Field o2) {
        V2Field a1 = o1.getAnnotation(V2Field.class), a2 = o2.getAnnotation(V2Field.class);
        if (a1 == a2) { return 0; }
        if (a1 == null && a2 != null) { return 1; }
        if (a1 != null && a2 == null) { return -1; }

        if (a1.value().equals(a2.value())) {
            return 0;
        }

        String seg1 = a1.value().substring(0, 3), seg2 = a2.value().substring(0, 3);
        int comp = segmentIndex(seg1) - segmentIndex(seg2);
        if (comp  != 0) {
            return comp;
        }

        String p1[] = a1.value().split("[-\\(\\)\\.]"),
               p2[] = a2.value().split("[-\\(\\)\\.]");

        for (int i = 1; i < Math.min(p1.length, p2.length); i++) {
            comp = Integer.parseInt(p1[i]) - Integer.parseInt(p2[i]);
            if (comp != 0) {
                return comp;
            }
        }
        return p1.length - p2.length;
    }
}
