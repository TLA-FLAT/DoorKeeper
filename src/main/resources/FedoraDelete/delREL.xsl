<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:fedora="info:fedora/fedora-system:def/relations-external#"
    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    exclude-result-prefixes="xs"
    version="2.0">
    
    <xsl:param name="sip"/>
    
    <xsl:template match="node() | @*">
        <xsl:copy>
            <xsl:apply-templates select="node() | @*"/>
        </xsl:copy>
    </xsl:template>
    
    <!-- delete relationship to SIP compound -->
    <xsl:template match="fedora:isConstituentOf[@rdf:resource=concat('info:fedora/',$sip)]"/>
    
</xsl:stylesheet>