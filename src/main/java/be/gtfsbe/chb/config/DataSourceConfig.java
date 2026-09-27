package be.gtfsbe.chb.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.session.jdbc.config.annotation.SpringSessionDataSource;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Twee volledig gescheiden pools op dezelfde database (schema chb): een read-only pool op de
 * chb_viewer-rol (gebruikt door alle publieke en alle lees-endpoints) en een schrijvende pool op
 * de chb_editor-rol (enkel gebruikt door beveiligde endpoints, na authenticatie). De scheiding
 * zit hier op DataSource-niveau, niet enkel in de Spring Security-laag: chb_viewer heeft in de
 * database geen enkel schrijfrecht (zie build-instruction.md §5 en §8).
 *
 * <p>{@code editorDataSource} is ook expliciet gemarkeerd als de {@link SpringSessionDataSource}:
 * Spring Session JDBC heeft schrijftoegang nodig (aanmaken/verlengen/opruimen van sessierijen) en
 * zonder deze qualifier zou {@link EnableJdbcHttpSession} falen op twee onduidelijke
 * {@link DataSource}-bonen (viewer/editor, geen van beide {@code @Primary}).
 *
 * <p><b>{@code @EnableJdbcHttpSession} i.p.v. enkel {@code spring.session.store-type=jdbc}</b>:
 * de eerste implementatie leunde puur op Boot's autoconfiguratie-property, wat stilzwijgend niets
 * deed (geen fout, geen sessie in de DB — enkel Tomcat's gewone in-memory sessie bleef actief).
 * Oorzaak: in Spring Boot 4.1 zit de klasse die die property leest/vertaalt naar een
 * {@code SessionRepositoryFilter}-boon niet in het huidige classpath (geverifieerd door de lokale
 * `.m2`-cache te doorzoeken op elke jar — nergens een {@code SessionAutoConfiguration}- of
 * {@code SpringSessionProperties}-klasse te vinden). {@code @EnableJdbcHttpSession} is Spring
 * Session's eigen, van Boot-autoconfiguratie onafhankelijke mechanisme (importeert
 * {@code JdbcHttpSessionConfiguration} rechtstreeks) en werkt dus sowieso, ongeacht welke
 * Boot-autoconfigure-submodule wel/niet toevallig meekomt.
 *
 * <p>{@code maxInactiveIntervalInSeconds = 259200} (72u): opgetrokken van Spring's default van 30
 * minuten, zodat een ingelogde curator niet elke halve dag opnieuw door de Entra ID-loginflow
 * moet — dit was zonder deze sessie-in-Postgres-opzet sowieso al zinloos, want elke herstart
 * verloor de sessie toch (zie klasse-Javadoc hierboven).
 */
@Configuration
@EnableJdbcHttpSession(tableName = "chb.spring_session", maxInactiveIntervalInSeconds = 259200)
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("app.datasource.viewer")
    public DataSourceProperties viewerDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource viewerDataSource(DataSourceProperties viewerDataSourceProperties) {
        return viewerDataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    public JdbcTemplate viewerJdbcTemplate(DataSource viewerDataSource) {
        return new JdbcTemplate(viewerDataSource);
    }

    @Bean
    @ConfigurationProperties("app.datasource.editor")
    public DataSourceProperties editorDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @SpringSessionDataSource
    public DataSource editorDataSource(DataSourceProperties editorDataSourceProperties) {
        return editorDataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    public JdbcTemplate editorJdbcTemplate(DataSource editorDataSource) {
        return new JdbcTemplate(editorDataSource);
    }

    @Bean
    public PlatformTransactionManager editorTransactionManager(DataSource editorDataSource) {
        return new DataSourceTransactionManager(editorDataSource);
    }
}
