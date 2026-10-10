package dev.eufin;

import static org.assertj.core.api.Assertions.*;

import dev.eufin.domain.*;
import dev.eufin.iso.IsoService;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RulesTest {
  @Test
  void validatesIbanAndNormalizes() {
    assertThat(Rules.iban("de89 3704 0044 0532 0130 00")).isEqualTo("DE89370400440532013000");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "DE89370400440532013001",
        "DE12",
        "ZZ89370400440532013000",
        "DE893704004405320130000",
        ""
      })
  void rejectsInvalidIban(String s) {
    assertThatThrownBy(() -> Rules.iban(s)).isInstanceOf(BankError.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "1.001", "100000000000000000.00"})
  void rejectsInvalidMoney(String s) {
    assertThatThrownBy(() -> Rules.money(new BigDecimal(s))).isInstanceOf(BankError.class);
  }

  @Test
  void normalizesMoneyWithoutRounding() {
    assertThat(Rules.money(new BigDecimal("2"))).isEqualByComparingTo("2.00");
  }

  @Test
  void routesAreExplicitSimulations() {
    assertThat(Rules.route("SCT_INST", "DE89370400440532013000")).isEqualTo("TIPS_SIM");
    assertThat(Rules.route("SCT_INST", "NL91ABNA0417164300")).isEqualTo("RT1_SIM");
    assertThatThrownBy(() -> Rules.route("WIRE", "DE")).isInstanceOf(BankError.class);
  }

  @Test
  void payeeComparisonAndXmlEscaping() {
    assertThat(Rules.nameMatches(" Alice   Doe ", "alice doe")).isTrue();
    assertThat(IsoService.escape("A&B<\"'")).isEqualTo("A&amp;B&lt;&quot;&apos;");
  }
}
