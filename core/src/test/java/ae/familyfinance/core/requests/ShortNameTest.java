package ae.familyfinance.core.requests;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShortNameTest {

    @Test
    void longShopTitlesBecomeBrandModelAndKind() {
        assertThat(ShortName.of("Sony WH-CH720N Wireless Noise Cancelling Over-Ear Headphones")).isEqualTo("Sony WH-CH720N headphones");
        assertThat(ShortName.of("JBL JR310BT Kids Wireless On-Ear Headphones")).isEqualTo("JBL JR310BT headphones");
        assertThat(ShortName.of("Philips Airfryer 3000 Series 4.1L")).isEqualTo("Philips Airfryer");
        assertThat(ShortName.of("Tefal Easy Fry 6-in-1 Air Fryer 4.2L")).isEqualTo("Tefal 6-in-1 air fryer");
        assertThat(ShortName.of("Apple iPad 10.9-inch Wi-Fi 64GB Tablet (10th generation)")).isEqualTo("Apple iPad tablet");
        assertThat(ShortName.of("Samsung 55-inch Crystal UHD 4K Smart TV")).isEqualTo("Samsung Crystal TV");
    }

    @Test
    void shortTitlesStayAsTheyAre() {
        assertThat(ShortName.of("Football for the park")).isEqualTo("Football for the park");
        assertThat(ShortName.of("LEGO City Fire Station 60320")).isEqualTo("LEGO City Fire Station 60320");
    }

    @Test
    void unknownKindKeepsTheFirstFourWords() {
        assertThat(ShortName.of("Haribo Goldbears Gummy Sweets 160g Party Pack")).isEqualTo("Haribo Goldbears Gummy Sweets");
    }
}
