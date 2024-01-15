<?xml version="1.0"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
	<xsl:output method="xml" encoding="utf-8"/>

	<xsl:template match="@* | node()">
		<xsl:copy>
			<xsl:apply-templates select="@* | node()"/>
		</xsl:copy>
	</xsl:template>

	<xsl:param name="dir"/>
	<xsl:param name="sip"/>

	<xsl:template match="configuration">
		<xsl:copy>
			<appender name="DEVEL" class="ch.qos.logback.core.FileAppender">
				<file>
					<xsl:value-of select="$dir"/>
/devel.log</file>
				<append>true</append>
				<filter class="ch.qos.logback.core.filter.EvaluatorFilter">
					<evaluator>
						<expression>return (mdc.get("sip") != null &amp;&amp; ((String)mdc.get("sip")).equals("<xsl:value-of select="$sip"/>
"));</expression>
					</evaluator>
					<OnMismatch>DENY</OnMismatch>
					<OnMatch>ACCEPT</OnMatch>
				</filter>
				<encoder>
					<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} : %msg%n</pattern>
				</encoder>
			</appender>
			<appender name="USER" class="ch.qos.logback.core.FileAppender">
				<file>
					<xsl:value-of select="$dir"/>
/user-log-events.xml</file>
				<append>true</append>\n" + <filter class="ch.qos.logback.core.filter.EvaluatorFilter">
				<evaluator>\n" + <expression>return (level >= INFO &amp;&amp; mdc.get("sip") != null &amp;&amp; ((String)mdc.get("sip")).equals("<xsl:value-of select="$sip"/>
"));</expression>
			</evaluator>
			<OnMismatch>DENY</OnMismatch>
			<OnMatch>ACCEPT</OnMatch>
		</filter>
		<encoder class="ch.qos.logback.core.encoder.LayoutWrappingEncoder">
			<layout class="ch.qos.logback.classic.log4j.XMLLayout"/>
		</encoder>
	</appender>
	<logger name="nl.mpi.tla.flat.deposit" level="DEBUG">
		<appender-ref ref="USER"/>
		<appender-ref ref="DEVEL"/>
	</logger>
	<xsl:apply-templates select="@* | node()"/>
</xsl:copy>
</xsl:template>

</xsl:stylesheet>
