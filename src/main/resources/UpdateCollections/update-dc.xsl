<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0"
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    exclude-result-prefixes="dc">

    <xsl:param name="new-pid"/>

    <xsl:variable name="new-url" select="replace($new-pid,'^hdl:','https://hdl.handle.net/')"/>

    <!-- Since FC6 the DC (and OLAC) datastream is the canonical XML record,
         e.g., to be served via OAI-PMH, while the object's RDF is a derived
         index of it. So replace the handle-shaped identifier(s) directly in
         the record, keeping everything else as-is. Results in <null/> when
         the handle is already up-to-date, i.e., no update is needed. -->

    <xsl:variable name="handles" select="//dc:identifier[matches(normalize-space(.),'^(hdl:|http(s)?://hdl.handle.net/)')]"/>

    <xsl:template match="/">
        <xsl:choose>
            <xsl:when test="count($handles)=1 and normalize-space($handles[1])=$new-url">
                <null/>
            </xsl:when>
            <xsl:otherwise>
                <xsl:apply-templates/>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>

    <!-- identity copy -->
    <xsl:template match="@*|node()">
        <xsl:copy>
            <xsl:apply-templates select="@*|node()"/>
        </xsl:copy>
    </xsl:template>

    <!-- no handle-shaped identifier yet, so add one -->
    <xsl:template match="/*[empty($handles)]">
        <xsl:copy>
            <xsl:apply-templates select="@*"/>
            <dc:identifier>
                <xsl:value-of select="$new-url"/>
            </dc:identifier>
            <xsl:apply-templates select="node()"/>
        </xsl:copy>
    </xsl:template>

    <!-- the first handle-shaped identifier gets the new PID, any further ones are dropped -->
    <xsl:template match="dc:identifier[matches(normalize-space(.),'^(hdl:|http(s)?://hdl.handle.net/)')]">
        <xsl:if test=". is $handles[1]">
            <xsl:copy>
                <xsl:apply-templates select="@*"/>
                <xsl:value-of select="$new-url"/>
            </xsl:copy>
        </xsl:if>
    </xsl:template>

</xsl:stylesheet>
