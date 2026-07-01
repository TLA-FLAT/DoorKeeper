<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0"
    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    xmlns:oai_dc="http://www.openarchives.org/OAI/2.0/oai_dc/"
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    exclude-result-prefixes="rdf oai_dc dc">

    <xsl:param name="new-pid"/>
    <!-- the RDF subject of the object, i.e., <localServer>/<fid> -->
    <xsl:param name="subject"/>

    <xsl:variable name="new-url" select="replace($new-pid,'^hdl:','https://hdl.handle.net/')"/>

    <!-- Since FC6 the DC record is folded into the object's RDF, so rebuild the
         oai_dc:dc record from the object's dc: properties, replacing the
         handle-shaped identifier(s) with the new PID. Results in <null/> when
         the handle is already up-to-date, i.e., no update is needed. -->
    <xsl:template match="/rdf:RDF">
        <xsl:variable name="props" select="rdf:Description[@rdf:about=$subject]/dc:*"/>
        <xsl:if test="empty($props)">
            <xsl:message>WRN: no DC properties found for subject[<xsl:value-of select="$subject"/>]!</xsl:message>
        </xsl:if>
        <xsl:variable name="handles" select="(for $id in $props[self::dc:identifier]
                                              return string(($id/text()[normalize-space(.)!=''],$id/@rdf:resource)[1]))[matches(.,'^(hdl:|http(s)?://hdl.handle.net/)')]"/>
        <xsl:choose>
            <xsl:when test="count($handles)=1 and $handles=$new-url">
                <null/>
            </xsl:when>
            <xsl:otherwise>
                <oai_dc:dc>
                    <dc:identifier>
                        <xsl:value-of select="$new-url"/>
                    </dc:identifier>
                    <xsl:for-each select="$props">
                        <xsl:variable name="val" select="string((text()[normalize-space(.)!=''],@rdf:resource)[1])"/>
                        <!-- skip the old handle(s) (replaced above) and, if present, a
                             repository-internal identifier equal to the subject itself -->
                        <xsl:if test="not(self::dc:identifier and ($val=$subject or matches($val,'^(hdl:|http(s)?://hdl.handle.net/)')))">
                            <xsl:element name="dc:{local-name()}" namespace="http://purl.org/dc/elements/1.1/">
                                <xsl:value-of select="$val"/>
                            </xsl:element>
                        </xsl:if>
                    </xsl:for-each>
                </oai_dc:dc>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>

</xsl:stylesheet>
