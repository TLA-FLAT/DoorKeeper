<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0"
    xmlns:oai_dc="http://www.openarchives.org/OAI/2.0/oai_dc/" xmlns:dc="http://purl.org/dc/elements/1.1/">
    
    <xsl:param name="new-pid"/>
    
    <xsl:template match="node() | @*">
        <xsl:copy>
            <xsl:apply-templates select="node() | @*"/>
        </xsl:copy>
    </xsl:template>
    
    <xsl:template match="oai_dc:dc">
        <xsl:copy>
            <xsl:apply-templates select="@*"/>
            <dc:identifier>
                <xsl:value-of select="replace($new-pid, '^hdl:', 'https://hdl.handle.net/')"/>
            </dc:identifier>
            <xsl:apply-templates select="node() except dc:identifier[matches(.,'^(hdl:|http(s)?://hdl.handle.net/).*$')]"/>
        </xsl:copy>        
    </xsl:template>
    
</xsl:stylesheet>