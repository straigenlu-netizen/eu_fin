package dev.eufin.iso;

import static dev.eufin.domain.Models.*;

import dev.eufin.domain.*;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** A deliberately restricted ISO profile: EUR, known internal accounts, one transaction. */
@Service
public class IsoService {
  private final BankService bank;
  private final JdbcTemplate db;
  private final Clock clock;

  public IsoService(BankService bank, JdbcTemplate db, Clock clock) {
    this.bank = bank;
    this.db = db;
    this.clock = clock;
  }

  public static String escape(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  private static String tag(String name, Object value) {
    return "<" + name + ">" + escape(value.toString()) + "</" + name + ">";
  }

  private static String account(String tag, String iban) {
    return "<" + tag + "><Id>" + tag("IBAN", iban) + "</Id></" + tag + ">";
  }

  private static String party(String tag, String name) {
    return "<" + tag + ">" + tag("Nm", name) + "</" + tag + ">";
  }

  private static String agent(String tag) {
    return "<" + tag + "><FinInstnId><BICFI>EUFIDEFFXXX</BICFI></FinInstnId></" + tag + ">";
  }

  private String document(String version, String body) {
    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Document"
            + " xmlns=\"urn:iso:std:iso:20022:tech:xsd:"
            + version
            + "\">"
            + body
            + "</Document>";
    validate(xml, version);
    return xml;
  }

  public void validate(String xml, String version) {
    if (xml.length() > 65536) throw BankError.invalid("XML_TOO_LARGE");
    if (!Set.of(
            "pain.001.001.09",
            "pain.008.001.08",
            "pacs.008.001.08",
            "pacs.003.001.08",
            "pacs.002.001.10",
            "pacs.004.001.09",
            "camt.053.001.08")
        .contains(version)) throw BankError.invalid("UNSUPPORTED_MESSAGE");
    try (var stream = getClass().getResourceAsStream("/iso/xsd/" + version + ".xsd")) {
      if (stream == null) throw new IllegalStateException("Missing XSD " + version);
      var f = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      f.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      var schema = f.newSchema(new StreamSource(stream));
      var validator = schema.newValidator();
      validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      validator.validate(new StreamSource(new StringReader(xml)));
    } catch (BankError e) {
      throw e;
    } catch (Exception e) {
      BankError error = BankError.invalid("INVALID_ISO_XML");
      error.initCause(e);
      throw error;
    }
  }

  private Document parse(String xml, String version) {
    validate(xml, version);
    try {
      var f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    } catch (Exception e) {
      BankError error = BankError.invalid("INVALID_ISO_XML");
      error.initCause(e);
      throw error;
    }
  }

  private static Element one(Element parent, String name) {
    var nodes = parent.getElementsByTagNameNS(parent.getNamespaceURI(), name);
    if (nodes.getLength() != 1) throw BankError.invalid("UNSUPPORTED_XML_PROFILE");
    return (Element) nodes.item(0);
  }

  private static void allowed(Element element, String... names) {
    Set<String> permitted = Set.of(names);
    for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling())
      if (n instanceof Element e && !permitted.contains(e.getLocalName()))
        throw BankError.invalid("UNSUPPORTED_XML_PROFILE");
  }

  private static String value(Element p, String name) {
    return one(p, name).getTextContent();
  }

  private String accountId(String iban) {
    return db
        .queryForList("select id from account where iban=?", String.class, Rules.iban(iban))
        .stream()
        .findFirst()
        .orElseThrow(() -> BankError.invalid("UNKNOWN_IBAN"));
  }

  public Payment importPain(Actor actor, String key, String xml) {
    Element root = parse(xml, "pain.001.001.09").getDocumentElement();
    Element group = one(root, "GrpHdr"), info = one(root, "PmtInf"), tx = one(info, "CdtTrfTxInf");
    allowed(root, "CstmrCdtTrfInitn");
    allowed(one(root, "CstmrCdtTrfInitn"), "GrpHdr", "PmtInf");
    allowed(group, "MsgId", "CreDtTm", "NbOfTxs", "CtrlSum", "InitgPty");
    allowed(one(group, "InitgPty"), "Nm");
    allowed(
        info,
        "PmtInfId",
        "PmtMtd",
        "PmtTpInf",
        "ReqdExctnDt",
        "Dbtr",
        "DbtrAcct",
        "DbtrAgt",
        "ChrgBr",
        "CdtTrfTxInf");
    allowed(tx, "PmtId", "Amt", "CdtrAgt", "Cdtr", "CdtrAcct");
    allowed(one(tx, "PmtId"), "EndToEndId");
    allowed(one(tx, "Amt"), "InstdAmt");
    allowed(one(info, "ReqdExctnDt"), "Dt");
    for (Element party : List.of(one(info, "Dbtr"), one(tx, "Cdtr"))) allowed(party, "Nm");
    for (Element acct : List.of(one(info, "DbtrAcct"), one(tx, "CdtrAcct"))) {
      allowed(acct, "Id");
      allowed(one(acct, "Id"), "IBAN");
    }
    for (Element agent : List.of(one(info, "DbtrAgt"), one(tx, "CdtrAgt"))) {
      allowed(agent, "FinInstnId");
      allowed(one(agent, "FinInstnId"), "BICFI");
      if (!"EUFIDEFFXXX".equals(value(agent, "BICFI")))
        throw BankError.invalid("EXTERNAL_AGENT_UNSUPPORTED");
    }
    if (info.getElementsByTagNameNS(root.getNamespaceURI(), "PmtTpInf").getLength() > 0) {
      Element pt = one(info, "PmtTpInf");
      allowed(pt, "SvcLvl", "LclInstrm");
      if (!"SEPA".equals(value(one(pt, "SvcLvl"), "Cd")))
        throw BankError.invalid("UNSUPPORTED_XML_PROFILE");
    }
    if (!"SLEV".equals(value(info, "ChrgBr"))) throw BankError.invalid("UNSUPPORTED_CHARGES");
    if (!"1".equals(value(group, "NbOfTxs")) || !"TRF".equals(value(info, "PmtMtd")))
      throw BankError.invalid("UNSUPPORTED_XML_PROFILE");
    // Refuse constructs that this educational engine does not execute.
    if (root.getElementsByTagNameNS(root.getNamespaceURI(), "UltmtDbtr").getLength() > 0
        || root.getElementsByTagNameNS(root.getNamespaceURI(), "UltmtCdtr").getLength() > 0
        || root.getElementsByTagNameNS(root.getNamespaceURI(), "RmtInf").getLength() > 0
        || root.getElementsByTagNameNS(root.getNamespaceURI(), "SplmtryData").getLength() > 0)
      throw BankError.invalid("UNSUPPORTED_XML_PROFILE");
    for (String reference : List.of(value(group, "MsgId"), value(info, "PmtInfId"), value(one(tx, "PmtId"), "EndToEndId")))
      Rules.sepaReference(reference);
    Rules.sepaName(value(one(group, "InitgPty"), "Nm"));
    Rules.sepaName(value(one(info, "Dbtr"), "Nm"));
    Rules.sepaName(value(one(tx, "Cdtr"), "Nm"));
    String date = value(one(info, "ReqdExctnDt"), "Dt");
    if (!LocalDate.parse(date).equals(LocalDate.now(clock)))
      throw BankError.invalid("SCHEDULED_PAYMENTS_UNSUPPORTED");
    Element amount = one(tx, "InstdAmt");
    if (!"EUR".equals(amount.getAttribute("Ccy"))) throw BankError.invalid("EUR_ONLY");
    BigDecimal n = Rules.money(new BigDecimal(amount.getTextContent()));
    if (group.getElementsByTagNameNS(root.getNamespaceURI(), "CtrlSum").getLength() > 0
        && new BigDecimal(value(group, "CtrlSum")).compareTo(n) != 0)
      throw BankError.invalid("CONTROL_SUM_MISMATCH");
    String debtor = accountId(value(one(info, "DbtrAcct"), "IBAN")),
        creditor = accountId(value(one(tx, "CdtrAcct"), "IBAN"));
    if (!Rules.nameMatches(value(one(info, "Dbtr"), "Nm"), bank.account(actor, debtor).name()))
      throw BankError.invalid("DEBTOR_NAME_MISMATCH");
    String scheme = "SCT";
    var local = root.getElementsByTagNameNS(root.getNamespaceURI(), "LclInstrm");
    if (local.getLength() > 0) {
      if (local.getLength() != 1 || !"INST".equals(value((Element) local.item(0), "Cd")))
        throw BankError.invalid("UNSUPPORTED_XML_PROFILE");
      scheme = "SCT_INST";
    }
    return bank.initiate(
        actor,
        key,
        new Initiate(
            debtor,
            creditor,
            n,
            scheme,
            value(one(tx, "PmtId"), "EndToEndId"),
            value(one(tx, "Cdtr"), "Nm"),
            null));
  }

  private static String timestamp(Instant instant) {
    return new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(instant);
  }

  private String mandateXml(Payment payment) {
    Map<String, Object> m = db.queryForMap("select * from mandate where id=?", payment.mandateId());
    if (m.get("mandate_reference") == null || m.get("creditor_identifier") == null
        || m.get("signature_date") == null || m.get("sequence_type") == null)
      throw BankError.invalid("SEPA_MANDATE_METADATA_REQUIRED");
    return "<MndtRltdInf>" + tag("MndtId", m.get("mandate_reference"))
        + tag("DtOfSgntr", m.get("signature_date").toString())
        + "</MndtRltdInf><CdtrSchmeId><Id><PrvtId><Othr>"
        + tag("Id", m.get("creditor_identifier"))
        + "<SchmeNm><Prtry>SEPA</Prtry></SchmeNm></Othr></PrvtId></Id></CdtrSchmeId>";
  }

  private String sequence(Payment payment) {
    return db.queryForObject("select sequence_type from mandate where id=?", String.class, payment.mandateId());
  }

  private String header(String id, int count) {
    return "<GrpHdr>"
        + tag("MsgId", shortId(id))
        + tag("CreDtTm", timestamp(clock.instant()))
        + tag("NbOfTxs", count)
        + "<SttlmInf><SttlmMtd>CLRG</SttlmMtd></SttlmInf></GrpHdr>";
  }

  private static String shortId(String id) {
    return id.replace("-", "");
  }

  public String export(Actor actor, String id, String type) {
    Payment p = bank.payment(actor, id);
    Account d = bank.account(new Actor("iso", true), p.debtorId()),
        c = bank.account(new Actor("iso", true), p.creditorId());
    Rules.sepaName(d.name());
    Rules.sepaName(c.name());
    Rules.sepaReference(p.endToEndId());
    String amount = p.amount().toPlainString();
    String pmt =
        "<PmtId>" + tag("EndToEndId", p.endToEndId()) + tag("TxId", shortId(id)) + "</PmtId>";
    String info =
        "<PmtTpInf><SvcLvl><Cd>SEPA</Cd></SvcLvl>"
            + (p.scheme().equals("SCT_INST") ? "<LclInstrm><Cd>INST</Cd></LclInstrm>" : "")
            + "</PmtTpInf>";
    return switch (type) {
      case "pacs008" -> {
        if (p.scheme().equals("SDD_CORE")) throw BankError.invalid("WRONG_MESSAGE_FOR_SCHEME");
        yield document(
            "pacs.008.001.08",
            "<FIToFICstmrCdtTrf>"
                + header(id, 1)
                + "<CdtTrfTxInf>"
                + pmt
                + info
                + "<IntrBkSttlmAmt Ccy=\"EUR\">"
                + amount
                + "</IntrBkSttlmAmt>"
                + tag("IntrBkSttlmDt", LocalDate.now(clock))
                + (p.scheme().equals("SCT_INST") ? tag("AccptncDtTm", timestamp(p.createdAt())) : "")
                + "<ChrgBr>SLEV</ChrgBr>"
                + party("Dbtr", d.name())
                + account("DbtrAcct", d.iban())
                + agent("DbtrAgt")
                + agent("CdtrAgt")
                + party("Cdtr", c.name())
                + account("CdtrAcct", c.iban())
                + "</CdtTrfTxInf></FIToFICstmrCdtTrf>");
      }
      case "pacs002" -> {
        String status =
            switch (p.status()) {
              case "SETTLED" -> "ACSC";
              case "REJECTED", "TIMED_OUT" -> "RJCT";
              case "CLEARED", "ROUTED" -> "ACSP";
              default -> "PDNG";
            };
        yield document(
            "pacs.002.001.10",
            "<FIToFIPmtStsRpt><GrpHdr>"
                + tag("MsgId", shortId(UUID.randomUUID().toString()))
                + tag("CreDtTm", timestamp(clock.instant()))
                + "</GrpHdr><OrgnlGrpInfAndSts>"
                + tag("OrgnlMsgId", shortId(id))
                + tag(
                    "OrgnlMsgNmId",
                    p.scheme().equals("SDD_CORE") ? "pacs.003.001.08" : "pacs.008.001.08")
                + "</OrgnlGrpInfAndSts><TxInfAndSts>"
                + tag("OrgnlEndToEndId", p.endToEndId())
                + tag("TxSts", status)
                + "</TxInfAndSts></FIToFIPmtStsRpt>");
      }
      case "pain001" -> {
        if (p.scheme().equals("SDD_CORE")) throw BankError.invalid("WRONG_MESSAGE_FOR_SCHEME");
        yield document(
            "pain.001.001.09",
            "<CstmrCdtTrfInitn><GrpHdr>"
                + tag("MsgId", shortId(id))
                + tag("CreDtTm", timestamp(clock.instant()))
                + "<NbOfTxs>1</NbOfTxs>"
                + tag("CtrlSum", amount)
                + party("InitgPty", d.name())
                + "</GrpHdr><PmtInf>"
                + tag("PmtInfId", shortId(id))
                + "<PmtMtd>TRF</PmtMtd>"
                + info
                + "<ReqdExctnDt>"
                + tag("Dt", LocalDate.now(clock))
                + "</ReqdExctnDt>"
                + party("Dbtr", d.name())
                + account("DbtrAcct", d.iban())
                + agent("DbtrAgt")
                + "<ChrgBr>SLEV</ChrgBr><CdtTrfTxInf><PmtId>"
                + tag("EndToEndId", p.endToEndId())
                + "</PmtId><Amt><InstdAmt Ccy=\"EUR\">"
                + amount
                + "</InstdAmt></Amt>"
                + agent("CdtrAgt")
                + party("Cdtr", c.name())
                + account("CdtrAcct", c.iban())
                + "</CdtTrfTxInf></PmtInf></CstmrCdtTrfInitn>");
      }
      case "pain008" -> {
        if (!p.scheme().equals("SDD_CORE")) throw BankError.invalid("WRONG_MESSAGE_FOR_SCHEME");
        mandateXml(p);
        yield document(
            "pain.008.001.08",
            "<CstmrDrctDbtInitn><GrpHdr>"
                + tag("MsgId", shortId(id))
                + tag("CreDtTm", timestamp(clock.instant()))
                + "<NbOfTxs>1</NbOfTxs>"
                + party("InitgPty", c.name())
                + "</GrpHdr><PmtInf>"
                + tag("PmtInfId", shortId(id))
                + "<PmtMtd>DD</PmtMtd><PmtTpInf><SvcLvl><Cd>SEPA</Cd></SvcLvl><LclInstrm><Cd>CORE</Cd></LclInstrm><SeqTp>" + escape(sequence(p)) + "</SeqTp></PmtTpInf>"
                + tag("ReqdColltnDt", LocalDate.now(clock))
                + party("Cdtr", c.name())
                + account("CdtrAcct", c.iban())
                + agent("CdtrAgt")
                + "<ChrgBr>SLEV</ChrgBr><DrctDbtTxInf><PmtId>"
                + tag("EndToEndId", p.endToEndId())
                + "</PmtId><InstdAmt Ccy=\"EUR\">"
                + amount
                + "</InstdAmt><DrctDbtTx>"
                + mandateXml(p)
                + "</DrctDbtTx>"
                + agent("DbtrAgt")
                + party("Dbtr", d.name())
                + account("DbtrAcct", d.iban())
                + "</DrctDbtTxInf></PmtInf></CstmrDrctDbtInitn>");
      }
      case "pacs003" -> {
        if (!p.scheme().equals("SDD_CORE")) throw BankError.invalid("WRONG_MESSAGE_FOR_SCHEME");
        mandateXml(p);
        yield document(
            "pacs.003.001.08",
            "<FIToFICstmrDrctDbt>"
                + header(id, 1)
                + "<DrctDbtTxInf>"
                + pmt
                + "<PmtTpInf><SvcLvl><Cd>SEPA</Cd></SvcLvl><LclInstrm><Cd>CORE</Cd></LclInstrm><SeqTp>" + escape(sequence(p)) + "</SeqTp></PmtTpInf><IntrBkSttlmAmt"
                + " Ccy=\"EUR\">"
                + amount
                + "</IntrBkSttlmAmt>"
                + tag("IntrBkSttlmDt", LocalDate.now(clock))
                + "<ChrgBr>SLEV</ChrgBr>"
                + tag("ReqdColltnDt", LocalDate.now(clock))
                + "<DrctDbtTx>"
                + mandateXml(p)
                + "</DrctDbtTx>"
                + party("Cdtr", c.name())
                + account("CdtrAcct", c.iban())
                + agent("CdtrAgt")
                + party("Dbtr", d.name())
                + account("DbtrAcct", d.iban())
                + agent("DbtrAgt")
                + "</DrctDbtTxInf></FIToFICstmrDrctDbt>");
      }
      case "pacs004" -> {
        if (!p.status().equals("RETURNED")) throw BankError.invalid("PAYMENT_NOT_RETURNED");
        yield document(
            "pacs.004.001.09",
            "<PmtRtr>"
                + header(UUID.randomUUID().toString(), 1)
                + "<TxInf>"
                + tag("RtrId", shortId(id))
                + "<OrgnlGrpInf>"
                + tag("OrgnlMsgId", shortId(id))
                + tag(
                    "OrgnlMsgNmId",
                    p.scheme().equals("SDD_CORE") ? "pacs.003.001.08" : "pacs.008.001.08")
                + "</OrgnlGrpInf>"
                + tag("OrgnlEndToEndId", p.endToEndId())
                + "<RtrdIntrBkSttlmAmt Ccy=\"EUR\">"
                + amount
                + "</RtrdIntrBkSttlmAmt><RtrRsnInf><Rsn><Prtry>LAB_RETURN</Prtry></Rsn></RtrRsnInf></TxInf></PmtRtr>");
      }
      default -> throw BankError.invalid("UNSUPPORTED_MESSAGE");
    };
  }

  public String statement(Actor actor, String id) {
    Account a = bank.account(actor, id);
    var lines = bank.statement(actor, id);
    StringBuilder entries = new StringBuilder();
    BigDecimal total = BigDecimal.ZERO;
    for (var l : lines) {
      BigDecimal n = (BigDecimal) l.get("delta");
      total = total.add(n);
      entries
          .append("<Ntry><Amt Ccy=\"EUR\">")
          .append(n.abs().toPlainString())
          .append("</Amt>")
          .append(tag("CdtDbtInd", n.signum() < 0 ? "DBIT" : "CRDT"))
          .append("<Sts><Cd>BOOK</Cd></Sts><BkTxCd><Prtry><Cd>LAB</Cd></Prtry></BkTxCd></Ntry>");
    }
    return document(
        "camt.053.001.08",
        "<BkToCstmrStmt><GrpHdr>"
            + tag("MsgId", shortId(UUID.randomUUID().toString()))
            + tag("CreDtTm", timestamp(clock.instant()))
            + "</GrpHdr><Stmt>"
            + tag("Id", shortId(id))
            + tag("CreDtTm", timestamp(clock.instant()))
            + account("Acct", a.iban())
            + balance("OPBD", BigDecimal.ZERO)
            + balance("CLBD", total)
            + entries
            + "</Stmt></BkToCstmrStmt>");
  }

  private String balance(String code, BigDecimal value) {
    return "<Bal><Tp><CdOrPrtry>"
        + tag("Cd", code)
        + "</CdOrPrtry></Tp><Amt Ccy=\"EUR\">"
        + value.abs().toPlainString()
        + "</Amt>"
        + tag("CdtDbtInd", value.signum() < 0 ? "DBIT" : "CRDT")
        + "<Dt>"
        + tag("Dt", LocalDate.now(clock))
        + "</Dt></Bal>";
  }

  /** Preview only: no claim of complete MT103 or CBPR+ conversion. Reject unmodelled fields. */
  public Map<String, String> previewMt(String text) {
    if (text.length() > 8192 || !text.startsWith(":20:"))
      throw BankError.invalid("UNSUPPORTED_MT103_PROFILE");
    Map<String, String> fields = new LinkedHashMap<>();
    String field = null;
    for (String line : text.replace("\r", "").split("\n")) {
      if (line.matches(":[0-9]{2}[A-Z]?:.*")) {
        int end = line.indexOf(':', 1);
        field = line.substring(1, end);
        if (fields.putIfAbsent(field, line.substring(end + 1)) != null)
          throw BankError.invalid("DUPLICATE_MT_FIELD");
      } else {
        if (field == null) throw BankError.invalid("UNSUPPORTED_MT103_PROFILE");
        fields.put(field, fields.get(field) + "\n" + line);
      }
    }
    if (!fields.keySet().equals(Set.of("20", "23B", "32A", "50K", "59", "71A"))
        || !"CRED".equals(fields.get("23B"))
        || !"SHA".equals(fields.get("71A"))
        || !fields.get("32A").matches("[0-9]{6}EUR[0-9]+,[0-9]{2}"))
      throw BankError.invalid("UNSUPPORTED_MT103_PROFILE");
    String[] d = fields.get("50K").split("\n"), c = fields.get("59").split("\n");
    if (d.length != 2 || c.length != 2 || !d[0].startsWith("/") || !c[0].startsWith("/"))
      throw BankError.invalid("UNSUPPORTED_MT103_PROFILE");
    return Map.of(
        "endToEndId",
        fields.get("20"),
        "currency",
        "EUR",
        "amount",
        Rules.money(new BigDecimal(fields.get("32A").substring(9).replace(',', '.')))
            .toPlainString(),
        "debtorIban",
        Rules.iban(d[0].substring(1)),
        "creditorIban",
        Rules.iban(c[0].substring(1)),
        "debtorName",
        d[1],
        "creditorName",
        c[1],
        "mode",
        "PREVIEW_ONLY_NOT_CBPR_CERTIFIED");
  }
}
