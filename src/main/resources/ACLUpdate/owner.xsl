<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="3.0">
    
    <xsl:param name="owner"/>
    
    <xsl:template name="main">
        <user>
            <name>
                <xsl:value-of select="$owner"/>
            </name>
        </user>
    </xsl:template>
    
</xsl:stylesheet>