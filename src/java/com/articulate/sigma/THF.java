/*** The KIF2THF converter file is a contribution by Christoph Benzmueller
 */

package com.articulate.sigma;

import java.util.*;
import java.util.regex.*;
import java.io.*;
import java.text.ParseException;


/** ************************************************************
 * This class handles the conversion of problems (= axioms + queries)
 * from their KIF representation into a THF representation; THF is the 
 * TPTP standard for classical higher-order logic, i.e. Church's simple
 * theory.
 *
 * The main function provided is KIF2THF(KIFaxioms,KIFqueries,KnowledgeBase)
 *
 * A challenge part in this transformation is the computation of an appropriate
 * typing for the KIF terms and formulas. This is partly non-trivial.
 * The conversion is intented to work purely syntactically (when no
 * typing-relevant information from SUMO is available) or mixed 
 * syntactically-semantically (when typing-relevant information from 
 * SUMO is available). 
 *
 * A small example:
 * The KIF Problem with axioms 
 *
 *  (holdsDuring (YearFN n2009) (enjoys Mary Cooking))
 *  (holdsDuring (YearFN n2009) (=> (instance ?X Female) (wants Ben ?X)))
 *  (holdsDuring ?X (instance Mary Female))
 *
 *  and Query
 * 
 *  (holdsDuring ?X (and (?Y Mary Cooking) (wants ?Z Mary)))
 *
 * is tranlated into the THF problem:
 * 
 *  %%% The extracted Signature %%%
 *   thf(holdsDuring,type,(holdsDuring: ($i>$o>$o))).
 *   thf(enjoys_THFTYPE_IiioI,type,(enjoys_THFTYPE_IiioI: ($i>$i>$o))).
 *   thf(female,type,(female: $i)).
 *   thf(n2009,type,(n2009: $i)).
 *   thf(cooking,type,(cooking: $i)).
 *   thf(ben,type,(ben: $i)).
 *   thf(yearFN_THFTYPE_IiiI,type,(yearFN_THFTYPE_IiiI: ($i>$i))).
 *   thf(mary,type,(mary: $i)).
 *   thf(wants,type,(wants: ($i>$i>$o))).
 *   thf(instance_THFTYPE_IiioI,type,(instance_THFTYPE_IiioI: ($i>$i>$o))).
 *
 *  %%% The translated axioms %%%
 *   thf(ax,axiom,((! [X: $i]: (holdsDuring @ X @ (instance_THFTYPE_IiioI @ mary @ female))))).
 *   thf(ax,axiom,((! [X: $i]: (holdsDuring @ (yearFN_THFTYPE_IiiI @ n2009) @ ((instance_THFTYPE_IiioI @ X @ female) => (wants @ ben @ X)))))).
 *   thf(ax,axiom,((holdsDuring @ (yearFN_THFTYPE_IiiI @ n2009) @ (enjoys_THFTYPE_IiioI @ mary @ cooking)))).
 *  
 *  %%% The translated conjectures %%%
 *   thf(con,conjecture,((? [X: $i,Y: $i,Z: $i]: (holdsDuring @ X @ ((enjoys_THFTYPE_IiioI @ mary @ Y) & (wants @ Z @ mary)))))).
 *
 * This THF problem can be solved effectively by TPTP THF compliant higher-order theorem provers.
 *
 * The transformation often needs to introduce several
 * 'copies' of KIF constants for different THF types. Therefore some constant
 * symbols become tagged with type information during the transformation process.
 * Example for tagged contant symbols above enjoys_THFTYPE_IiioI and 
 * instance_THFTYPE_IiioI.
 */

public class THF {


    /** ***************************************************************
     * THFdebug: variable for enabling/diabling debugging mode; when set then 
     * there will be useful information printed
     */
    private static Boolean THFdebug = false;


    /** ***************************************************************
     * A debug print function (uses variable THFdebug)
     */
    private static String THFdebugOut (String str) {
        if (THFdebug) {
            System.out.println(str);
            return str;
        }
        return "";
    }

    /** ***************************************************************
     * A string builder containing the dynamically modified 
     * KIF formula during the KIF2THF transformation process
     */
    private StringBuilder kifFormula = new StringBuilder();

    /** ***************************************************************
     * A map containing relevant information on the dynamically changing 
     * set of constant symbols during the KIF2THF transformation. This
     * is used in the 'tagging' of constant symbols with type information.
     */
    private HashMap subst = new HashMap();

    /** ***************************************************************
     * Two maps from THF constant symbols to THF types as built up by the 
     * KIF2THF transformation
     */
    private HashMap localsig = new HashMap();
    private HashMap overallsig = new HashMap();


    /** ***************************************************************
     * A map from THF (sub-)terms to types as exploited by the KIF2THF
     * transformation
     */
    private HashMap terms = new HashMap();

    /** ***************************************************************
     * A variable that defines the THF type delimiter (cf. $i > $o)
     */
    private static String typeDelimiter = ">";

    /** ***************************************************************
     * A variable that defines the THF 'not translated' string
     */
    private static String notTranslatedStr   = "%%% notTranslated: ";

    /** ***************************************************************
     * Declaration of some special THF types used in the KIF2THF translation.
     * In the final translation only the THF base types $i (individuals) and
     * $o (Booleans) should be occuring.
     */
    private String boolTp = "$o";  // THF type for Booleans
    private String indTp = "$i";   // THF type for individuals
    private String numTp = "num";   // THF type for numbers (preliminary)
    private String unknownTp = "uknTP"; // the 'unknown' help type, kind of polymorphic 
    private String problemTp = "probTp"; // the 'problem' help type, for real type clashes

    /** ***************************************************************
     * A function that checks whether a given term-to-type mapping
     * such as 'terms' or 'localsig' above contain some type information
     * involving the 'unkownTp'.
     *
     * @param a term-to-type mapping (where types are encoded as strings) 
     */
    private boolean containsUnknownTp(HashMap map) {
        Collection entries = map.values();
        THFdebugOut("\n   Enter containsUnknownTp with entries = " + entries.toString());

        boolean found = false;

        for (Iterator it = entries.iterator(); it.hasNext();) {
            String entry = (String) it.next();
            if (entry.contains(unknownTp)) {
                found = true;
            }
        }

        THFdebugOut("\n   Exit containsUnknownTp with found = " + found);
        return found;   
    }

    /** ***************************************************************
     * A special function that replaces all occurences of key by 
     * keysubst in a formula string str. The special aspects here is that we do
     * not want to replace substrings of constants, e.g.
     * applySubstTo("what","which","(whatever (what (somewhat what)))")
     * returns "(whatever (which (somewhat which)))"
     * and not "(whichever (what (somewhich what)))"   
     *
     * @param a string to replace 
     *
     * @param the string to use as replacement
     *
     * @param the string to apply the substitution to
     * 
     */
    private String applySubstTo(String key, String keysubst, String str) {

        String key1 = key + " ";
        String keysubst1 = keysubst + " ";
        String key2 = key + "\\)";
        String keysubst2 = keysubst + ")";

        str = str.replaceAll(key1,keysubst1);
        str = str.replaceAll(key2,keysubst2);

        return str;
    }

    /** ***************************************************************
     * The main function to convert KIF problems into TPTP THF representation;
     * see the explanation at top of this file.
     * This is the only public function of THF.java so far.
     *
     * @param axioms is a list of KIF axiom formulas
     *
     * @param conjectures is a list of KIF query formulas
     *
     * @param kb is a knowledge base, e.g. SUMO
     *
     */

    /**
    public String KIF2THF(Collection<Formula> axioms, Collection<Formula> conjectures, KB kb) {

	// string builders for the essential results of the translation:
	// signature, axioms, and conjectures
	StringBuilder signatureResult = new StringBuilder();	
	signatureResult.append("\n%%% The extracted Signature %%%");
	StringBuilder axiomsResult = new StringBuilder();
	axiomsResult.append("\n\n%%% The translated axioms %%%");
	StringBuilder conjecturesResult = new StringBuilder();	
	conjecturesResult.append("\n\n%%% The translated conjectures %%%");

	// tags and a map to distinguish axioms from conjectures
	String axTag = "ax";
	String conTag = "con";
	HashMap taggedFormulas = new HashMap();
	for (Iterator iter = axioms.iterator(); iter.hasNext();) {
	    Formula ax = (Formula) iter.next();
	    taggedFormulas.put(ax,axTag);
	}
	for (Iterator iter = conjectures.iterator(); iter.hasNext();) {
	    Formula con = (Formula) iter.next();
	    taggedFormulas.put(con,conTag);
	}

	// the main loop; we proceed formula by formula and work with side effects
	// to variables introduced above (I know that this is terrible programming style!) 
	for (Iterator iter = taggedFormulas.keySet().iterator(); iter.hasNext();) {
	    Formula form = (Formula) iter.next();

	    // formula f contains the explicitly quantified formula under
	    // consideration, the quantifier (universal/existential) is 
	    // determined correctly for axioms and conjectures 
	    Formula f = new Formula();
	    if (taggedFormulas.get(form).equals(axTag)) {
		f.read(form.makeQuantifiersExplicit(false));
	    }
	    else if (taggedFormulas.get(form).equals(conTag)) {
		f.read(form.makeQuantifiersExplicit(true));
	    }

	    System.out.println("\nKIF2THF -- translating KIF formula: " + f.theFormula.trim());

	    // we request some semantic type-relevant information on the function and
	    // relation symbols involved; this information is used with priority below
	    HashMap relTypeInfo = f.gatherRelationsWithArgTypes(kb);

	    // we initialize the terms-to-types mapping and start the actual translation 
	    terms = new HashMap();
	    String res = toTHF1(f,boolTp,relTypeInfo);

	    // toTHF1 may return a THF translation that still contains many occurences
	    // of the (kind of) polymorphic 'unkownTp' and in this case we apply further 
	    // translation attempts employing the incrementially refined term-to-type 
	    // information. This is done via repetitive calls to toTHF2. This loop 
	    // terminates when the signature localsig, which is storing the latest constant-to-symbols
	    // mapping, is free of occurences of the 'unknownTp'.
	    // toTHF2 always starts a fresh translation attempt for the KIF formula stored in 
	    // variable kifFormula, which may itself be modified by renamings of symbols.
	    // It is thus important in the code to maintain a correspondence between the symbols in 
	    // this kifFormula, the terms-to-type mappings, and the incrementially refined THF
	    // translation; the handling of e.g. the different upper and lower case conventions 
	    // between KIF and TPTP THF further complicates matters. This issue makes the code
	    // particularly fragile, also since it exploits string processing way to much.
	    HashMap oldsig = new HashMap();
	    kifFormula = new StringBuilder();
	    while (containsUnknownTp(localsig)) {
		if (!oldsig.equals(localsig)) {
		    THFdebugOut("\n Debug: Enter new regular topmost call to THF2");
		    oldsig = (HashMap) localsig.clone();
		    res = toTHF2(f);
		    f = new Formula();
		    f.read(kifFormula.toString());
		    kifFormula = new StringBuilder();
		    localsig = clearMapFor(localsig,f.theFormula.trim());
		    subst = clearMapFor(subst,f.theFormula.trim());
		}
		else {
		    THFdebugOut("\n Debug: Enter new topmost call to THF2 with constant symbol substitution");
		    oldsig = (HashMap) localsig.clone();
		    Set keyset = subst.keySet();
		    THFdebugOut("   Debug: f before is " + f.toString());
		    THFdebugOut("   Debug: subst is " + subst.toString());
		    String fsubst = f.toString();
		    for (Iterator it = keyset.iterator(); it.hasNext();) {
			String key = (String) it.next();
			String keysubst = (String) subst.get(key);
			THFdebugOut("\n    Debug: fsubst before is " + fsubst);
			THFdebugOut("    Debug: key is " + key + " and keysubst is " + keysubst);
			fsubst = applySubstTo(key,keysubst,fsubst);
			THFdebugOut("    Debug: fsubst after is " + fsubst);
		    }
		    f = new Formula();
		    f.read(fsubst);
		    THFdebugOut("\n   Debug: f after is " + f.toString());
		    res = toTHF2(f);
		    f = new Formula();
		    f.read(kifFormula.toString());
		    kifFormula = new StringBuilder();
		    localsig = clearMapFor(localsig,f.theFormula.trim());
		    subst = clearMapFor(subst,f.theFormula.trim());
		}
	    }

	    // this final one-more call to toTHF2 seems not needed anymore
	    // but is was in earlier versions.
	    // if  (res.contains(unknownTp)) {
	    // THFdebugOut("\n Debug: Enter one more topmost call to THF2");

	    res = toTHF2(f);

	    // }

	    // now we can add the computed THF translation for 
	    // formula f to the appropriate result string builder
	    if (taggedFormulas.get(form).equals(axTag)) {
		String resAx = "";
		if (res.startsWith("%")) {
		    resAx = "\n" + res;
		}
		else {
		    resAx = "\n thf(ax,axiom,(" + res + ")).";
		}
		System.out.println("KIF2THF -- result: " + resAx);
		axiomsResult.append(resAx);
	    }
	    else if (taggedFormulas.get(form).equals(conTag)) {
		String resCon = "";
		if (res.startsWith("%")) {
		    resCon = "\n" + res;
		}
		else {
		    resCon = "\n thf(con,conjecture,(" + res + ")).";
		}
		System.out.println("KIF2THF -- result: " + resCon);
		conjecturesResult.append(resCon);
	    }
	    else {
		axiomsResult.append("\n something went wrong for " + form + "   " + res);
	    }
	    overallsig.putAll(localsig);
	    localsig = new HashMap();
	}

	// After the translation processed has terminated for all formulas f, we read off
	// the THF signature from the map 'overallsig'
	Set constants = overallsig.keySet();	
	for (Iterator it = constants.iterator(); it.hasNext();) {
	    String con = (String) it.next();
	    String ty = (String) overallsig.get(con);
	    signatureResult.append("\n thf(" + con + ",type,(" + con + ": " + ty + ")).");
	}

	String result = signatureResult.toString() + axiomsResult.toString() + conjecturesResult.toString();
	return result.toString();
    }

     */

    public String KIF2THF(Collection<Formula> axiomsC, Collection<Formula> conjecturesC, KB kb) {


	Collection axioms = new LinkedHashSet();
	Collection conjectures = new LinkedHashSet();

	for (Iterator iter = axiomsC.iterator(); iter.hasNext();) {
            Formula ax = (Formula) iter.next();
            axioms.addAll(ax.expandRowVars(kb));
        }

	for (Iterator iter = conjecturesC.iterator(); iter.hasNext();) {
            Formula con = (Formula) iter.next();
	    conjectures.addAll(con.expandRowVars(kb));
        }

        //not sort the formulas
        //Collection axioms = axiomsC;
        //Collection conjectures = conjecturesC;

        // initialize the global signature
        overallsig = new HashMap();

        // string builders for the essential results of the translation:
        // signature, axioms, and conjectures
        StringBuilder signatureResult = new StringBuilder();	
        signatureResult.append("\n%%% The extracted Signature %%%");
        StringBuilder axiomsResult = new StringBuilder();
        axiomsResult.append("\n\n%%% The translated axioms %%%");
        StringBuilder conjecturesResult = new StringBuilder();	
        conjecturesResult.append("\n\n%%% The translated conjectures %%%");

        // tags and a map to distinguish axioms from conjectures
        String axTag = "ax";
        String conTag = "con";
        HashMap taggedFormulas = new HashMap();
        for (Iterator iter = axioms.iterator(); iter.hasNext();) {
            Formula ax = (Formula) iter.next();
            taggedFormulas.put(ax,axTag);
        }
        for (Iterator iter = conjectures.iterator(); iter.hasNext();) {
            Formula con = (Formula) iter.next();
            taggedFormulas.put(con,conTag);
        }

        // the main loop; we proceed formula by formula and work with side effects
        // to variables introduced above (I know that this is terrible programming style!) 
  
        int axcounter = 1;
        int concounter = 1;
        for (Iterator iter = sortFormulas2(taggedFormulas.keySet()).iterator(); iter.hasNext();) {
            Formula form = (Formula) iter.next();

            // formula f contains the explicitly quantified formula under
            // consideration, the quantifier (universal/existential) is 
            // determined correctly for axioms and conjectures 
            Formula f = new Formula();
            if (taggedFormulas.get(form).equals(axTag)) {
                f.read(form.makeQuantifiersExplicit(false));
            }
            else if (taggedFormulas.get(form).equals(conTag)) {
                f.read(form.makeQuantifiersExplicit(true));
            }

            System.out.println("\nKIF2THF -- translating KIF formula: " + f.theFormula.trim());

            // we request some semantic type-relevant information on the function and
            // relation symbols involved; this information is used with priority below
            HashMap relTypeInfo = f.gatherRelationsWithArgTypes(kb);

            // we initialize the terms-to-types mapping and start the actual translation 
            terms = (HashMap) overallsig.clone();
            String res = toTHF1(f,boolTp,relTypeInfo);

            // toTHF1 may return a THF translation that still contains many occurences
            // of the (kind of) polymorphic 'unkownTp' and in this case we apply further 
            // translation attempts employing the incrementially refined term-to-type 
            // information. This is done via repetitive calls to toTHF2. This loop 
            // terminates when the signature localsig, which is storing the latest constant-to-symbols
            // mapping, is free of occurences of the 'unknownTp'.
            // toTHF2 always starts a fresh translation attempt for the KIF formula stored in 
            // variable kifFormula, which may itself be modified by renamings of symbols.
            // It is thus important in the code to maintain a correspondence between the symbols in 
            // this kifFormula, the terms-to-type mappings, and the incrementially refined THF
            // translation; the handling of e.g. the different upper and lower case conventions 
            // between KIF and TPTP THF further complicates matters. This issue makes the code
            // particularly fragile, also since it exploits string processing way to much.
            HashMap oldsig = new HashMap();
            //localsig = new HashMap();

            kifFormula = new StringBuilder();
            while (containsUnknownTp(localsig)) {
                if (!oldsig.equals(localsig)) {
                    THFdebugOut("\n Debug: Enter new regular topmost call to THF2");
                    oldsig = (HashMap) localsig.clone();
                    res = toTHF2(f);
                    f = new Formula();
                    f.read(kifFormula.toString());
                    kifFormula = new StringBuilder();
                    localsig = clearMapFor(localsig,f.theFormula.trim());
                    subst = clearMapFor(subst,f.theFormula.trim());
                }
                else {
                    THFdebugOut("\n Debug: Enter new topmost call to THF2 with constant symbol substitution");
                    oldsig = (HashMap) localsig.clone();
                    Set keyset = subst.keySet();
                    THFdebugOut("   Debug: f before is " + f.toString());
                    THFdebugOut("   Debug: subst is " + subst.toString());
                    String fsubst = f.toString();
                    for (Iterator it = keyset.iterator(); it.hasNext();) {
                        String key = (String) it.next();
                        String keysubst = (String) subst.get(key);
                        THFdebugOut("\n    Debug: fsubst before is " + fsubst);
                        THFdebugOut("    Debug: key is " + key + " and keysubst is " + keysubst);
                        fsubst = applySubstTo(key,keysubst,fsubst);
                        THFdebugOut("    Debug: fsubst after is " + fsubst);
                    }
                    f = new Formula();
                    f.read(fsubst);
                    THFdebugOut("\n   Debug: f after is " + f.toString());
                    res = toTHF2(f);
                    f = new Formula();
                    f.read(kifFormula.toString());
                    kifFormula = new StringBuilder();
                    localsig = clearMapFor(localsig,f.theFormula.trim());
                    subst = clearMapFor(subst,f.theFormula.trim());
                }
            }

            // this final one-more call to toTHF2 seems not needed anymore
            // but is was in earlier versions.
            // if  (res.contains(unknownTp)) {
            // THFdebugOut("\n Debug: Enter one more topmost call to THF2");

            res = toTHF2(f);
            localsig = clearMapSpecial(localsig,f.theFormula.trim());

            // }

            // now we can add the computed THF translation for 
            // formula f to the appropriate result string builder
            if (taggedFormulas.get(form).equals(axTag)) {
                String resAx = "";
                if (res.startsWith(notTranslatedStr)) {
                    resAx = "\n\n" + res;
                }
		else
		    if (res.indexOf(notTranslatedStr) != -1) {
                    resAx = "\n\n" + notTranslatedStr + res;
		    }
		    else {
			resAx = "\n\n thf(ax"+axcounter+",axiom,(" + res + ")).";
			axcounter++;
		    }
                System.out.println("KIF2THF -- result: " + resAx);
                axiomsResult.append(resAx);
            }
            else if (taggedFormulas.get(form).equals(conTag)) {
                String resCon = "";
                if (res.startsWith(notTranslatedStr)) {
                    resCon = "\n\n" + res;
                }
		else
		    if (res.indexOf(notTranslatedStr) != -1) {
                    resCon = "\n\n" + notTranslatedStr + res;
		    }
		    else {
			resCon = "\n\n thf(con"+concounter+",conjecture,(" + res + ")).";
			concounter++;
		    }
                System.out.println("KIF2THF -- result: " + resCon);
                conjecturesResult.append(resCon);
            }
            else {
                axiomsResult.append("\n something went wrong for " + form + "   " + res);
            }
            overallsig.putAll(localsig);
            localsig = new HashMap();
        }

        // After the translation processed has terminated for all formulas f, we read off
        // the THF signature from the map 'overallsig'
        signatureResult.append("\n thf(numbers,type,(" + numTp + ": $tType)).");
        Set constants = overallsig.keySet();
        List constantsL = new ArrayList(constants);
        Collections.sort(constantsL);
        for (Iterator it = constantsL.iterator(); it.hasNext();) {
            String con = (String) it.next();
            String ty = (String) overallsig.get(con);
            signatureResult.append("\n thf(" + con + ",type,(" + con + ": " + ty + ")).");
        }

        String result = signatureResult.toString() + axiomsResult.toString() + conjecturesResult.toString();
        return result.toString();
    }



    /** ***************************************************************
     * A function that clears a given term-to-type mapping for a given
     * formula string. The returned term-to-type only contains the entries
     * from the original mapping that are actually occuring in the string.
     *
     * @param map is a term-to-type mapping (both represented as strings)
     *
     * @param f is a formula string 
     *
     */
    private HashMap clearMapFor(HashMap map, String f) {

        HashMap copyMap = (HashMap) map.clone();

        THFdebugOut("\n  Enter clearMapFor with " + f + " \n  map is " + map.toString());
        Set keyset = map.keySet();
        for (Iterator it = keyset.iterator(); it.hasNext();) {
            String key = (String) it.next();
            String key1 = "(" + key + " ";
            String key2 = " " + key + " ";
            String key3 = " " + key + ")";
            // Pattern p = Pattern.compile(".*([\\(\\s]" + key + "[\\)\\s]).*");
            // Matcher m = p.matcher(f);
            // boolean b = m.matches();
            if (!(f.contains(key1) || f.contains(key2) || f.contains(key3))) {
                copyMap.remove(key);
            }
        }

        THFdebugOut("\n  Exit clearMapFor \n  map is " + localsig.toString());	
        return copyMap;
    }


    /** ***************************************************************
     * A function that clears a given term-to-type mapping for all
     * entries that do not contain the _THFTPTP_ substring
     *
     * @param map is a term-to-type mapping (both represented as strings)
     *
     * @param f is a formula string 
     *
     */

    private HashMap clearMapSpecial(HashMap map, String f) {

        HashMap copyMap = (HashMap) map.clone();

        THFdebugOut("\n  Enter clearMapSpecial with " + f + " \n  map is " + map.toString());
        Set keyset = map.keySet();
        for (Iterator it = keyset.iterator(); it.hasNext();) {
            String key = (String) it.next();
            if (!key.contains("_THFTYPE_")) {
                copyMap.remove(key);
            }
        }

        THFdebugOut("\n  Exit clearMapSpecial\n  map is " + localsig.toString());	
        return copyMap;
    }


    /** ***************************************************************
     * A function that converts a SUMO 'type' information into a THF type
     *
     * @param intype is the SUMO type 
     *
     */
    private String KIFType2THF(String intype) {

        THFdebugOut("\n  Enter KIFType2THF with intype=" + intype);
        HashMap convertTypeInfo = new HashMap();
        /* some default cases */
        /* convertTypeInfo.put(null,unknownTp); */
        convertTypeInfo.put(unknownTp,unknownTp);
        convertTypeInfo.put(boolTp,boolTp);
        convertTypeInfo.put(indTp,indTp);
        /* unknowns */
        convertTypeInfo.put("Entity",unknownTp);
        convertTypeInfo.put("Object",unknownTp);
        /* Booleans */
        convertTypeInfo.put("Formula",boolTp);
        convertTypeInfo.put("Proposition",boolTp);
        convertTypeInfo.put("Argument",boolTp);
        convertTypeInfo.put("Sentence",boolTp);
        convertTypeInfo.put("TruthValue",boolTp);
        /* Numbers (numTp does not work) */
        convertTypeInfo.put("Integer",indTp);
        convertTypeInfo.put("RealNumber",indTp);
        convertTypeInfo.put("Quantity",indTp);
        convertTypeInfo.put("PhysicalQuantity",indTp);
        /* sets (if we enable this, then we run into problems) */ 
        //String setTpPattern = "(" + unknownTp + typeDelimiter + boolTp + ")";
        //convertTypeInfo.put("SetOrClass", setTpPattern);
        //convertTypeInfo.put("Collection", setTpPattern);
        //convertTypeInfo.put("FamilyGroup", setTpPattern);
        //convertTypeInfo.put("TimeInterval", setTpPattern);
        /* arbitrary relations */
        convertTypeInfo.put("Relation", unknownTp);
        /* binary relations */
        String binrelTpPattern = "(" + unknownTp + typeDelimiter + unknownTp + typeDelimiter + boolTp + ")";
        convertTypeInfo.put("BinaryRelation", binrelTpPattern);
        convertTypeInfo.put("BinaryPredicate", binrelTpPattern);
        convertTypeInfo.put("CaseRole", binrelTpPattern);
        /* ternary relations */
        String ternrelTpPattern = "(" + unknownTp + typeDelimiter + unknownTp + typeDelimiter + unknownTp + typeDelimiter + boolTp + ")";
        convertTypeInfo.put("TernaryRelation", ternrelTpPattern);
        /* quaternary relations */
        String quaternrelTpPattern = "(" + unknownTp + typeDelimiter + unknownTp + typeDelimiter + unknownTp + typeDelimiter + unknownTp + typeDelimiter + boolTp + ")";
        convertTypeInfo.put("QuaternaryRelation", quaternrelTpPattern);
        /* unary functions */
        String ufunTpPattern = "(" + unknownTp + typeDelimiter + unknownTp + ")";
        convertTypeInfo.put("UnaryFunction", ufunTpPattern);
        /* binary functions */
        String binfunTpPattern = "(" + unknownTp + typeDelimiter + unknownTp + typeDelimiter + unknownTp + ")";
        convertTypeInfo.put("BinaryFunction", binfunTpPattern);
        /* ternary functions */
        String ternfunTpPattern = "(" + unknownTp + typeDelimiter + unknownTp + typeDelimiter + unknownTp + typeDelimiter + unknownTp + ")";
        convertTypeInfo.put("TernaryFunction", ternfunTpPattern);
        /* quaternary functions */
        String quatfunTpPattern = "(" + unknownTp + typeDelimiter + unknownTp + typeDelimiter + unknownTp + typeDelimiter + unknownTp + typeDelimiter + unknownTp + ")";
        convertTypeInfo.put("QuaternaryFunction", quatfunTpPattern);

        String res = "";
        if (convertTypeInfo.containsKey(intype)) {
            res = (String) convertTypeInfo.get(intype);
        }
        else {
            res = indTp;
        }

        THFdebugOut("\n  Exit KIFType2THF with " + res);

        return res;
    }


    /** ***************************************************************
     * A predicate that checks whether a THF type is a base type
     *
     * @param intype is the THF type 
     *
     */
    private boolean isBaseTp (String intype) {

        boolean res = false;

        if (intype.equals(unknownTp) || intype.equals(indTp)) {
            res = true;
        }

        return res;
    }


    /** ***************************************************************
     * A function that grounds a THF type, that is replaces all occurences
     * of 'unknownTp' by $iinformation into a THF type
     *
     * @param intype is the THF type 
     *
     */
    private String groundType(String sym, String intype) {

        THFdebugOut("\n  Enter groundType with sym=" + sym + " intype=" + intype);
        String res = intype;

        if (intype.equals(unknownTp)) {

            // we check whether the overallsig contains some interesting types already for sym
            Set osigkeyset = overallsig.keySet(); 
            List<String> candidateTypes = new ArrayList();
            for (Iterator it = osigkeyset.iterator(); it.hasNext();) {
                String entry = (String) it.next();	    
                if (entry.startsWith(sym + "_")) {
                    THFdebugOut("\n  Inside groundType: sym " + entry + " startsWith " + sym);
                    String entryTp = (String) overallsig.get(entry);
                    THFdebugOut("\n  Inside groundType: type of " + entry + " is " + entryTp);
                    candidateTypes.add(entryTp);
                }
            }

            // if overallsig contains some interesting types already for sym the take the first
            if (!candidateTypes.isEmpty()) {
                res = (String) candidateTypes.get(0);
            }
            // if not then simply choose indTp
            else {
                res = indTp;
            }
        }

        else {   
            res = intype.replaceAll(unknownTp,"\\" + indTp);
        }

        //System.out.println("\n  Exit groundType with " + res);
        THFdebugOut("\n  Exit groundType with " + res);
        return res;
    }

    /** ***************************************************************
     * A predicate that checks whether some symbol string represents 
     * a KIF variable
     *
     * @param sym is the input symbol to analyse
     *
     */
    private boolean isKifVar (String sym) {
        if ((sym.startsWith("?")) || (sym.startsWith("@"))) {
            return true;
        }
        else {
            return false;
        }
    }

    /** ***************************************************************
     * A predicate that checks whether some symbol string represents 
     * a KIF variable
     *
     * @param sym is the input symbol to analyse
     *
     */
    private boolean isKifConst (String sym) {
        if (!sym.startsWith("?") && !sym.startsWith("@") && !sym.startsWith("(")) {
            return true;
        }
        else {
            return false;
        }
    }



    /** ***************************************************************
     * A function that converts a KIF variable into a THF variable
     *
     * @param var  is the KIF variable
     *
     */
    private String toTHFKifVar(String var) {
        String res = var.replaceAll("\\-","_");
        return res.substring(1).toUpperCase();
    }

    /** ***************************************************************
     * A function that converts a KIF constant symbol into a THF constant
     *
     * @param sym is the KIF constant symbol
     *
     */
    private String toTHFKifConst(String sym) {

        THFdebugOut("\n  Enter toTHFKifConst: " + sym);
        String res = sym.replaceAll("\\.","dot").replaceAll("\\-","minus").replaceAll("\\+","plus");
        String c0 = res.substring(0,1);
        char c = c0.toCharArray()[0];
        if (Character.isDigit(c)) {
            res = res.replaceFirst(c0,"n" + c0);
        }
        else if (Character.isUpperCase(c)) { 
            res = res.replaceFirst(c0,"l" + c0);
        }
        THFdebugOut("\n  Exit toTHFKifConst: " + res);
        return res;
    }


    /** ***************************************************************
     * A help function for toTHF1; this help function addresses the THF
     * conversion of formulas with logical or arithmetic connective
     * at head position
     *
     * @param f is the KIF formula to convert
     *
     * @param op_thf is the THF connective to use at head postion
     *
     * @param goalTp is the THF type suggested for this formula
     *
     * @param  argsTp is the THF type suggested for the arguments
     *
     * @param preferPrefix signals if prefix or infix conversion is preferred
     *
     * @param relTpInfo is the passed on semantic 'type' information for symbols in f
     *
     */
    private String toTHFHelp1 (Formula f, String op_thf, String goalTp, String argsTp, boolean preferPrefix, HashMap relTpInfo) {

        THFdebugOut("\n  Debug: logical connective at head position in " + f.theFormula);

        // resTerm will contain the result
        StringBuilder resTerm = new StringBuilder();
        Formula f1 = new Formula();
        f1.read(f.getArgument(1));

        // we perform a (recursive) call to toTHF1 for the first argument
        String arg1 = toTHF1(f1,argsTp,relTpInfo);

        // we similarly work of the remaining arguments and distinguish between the prefix
        // and the infix case
        if (preferPrefix) {
            resTerm.append("(" + op_thf + " @ " + arg1);

            int len = f.listLength();
            for (int i = 2; i < len; i++) {
                Formula fi = new Formula();
                fi.read(f.getArgument(i));
                String argi = toTHF1(fi,argsTp,relTpInfo);
                resTerm.append(" @ " + argi);
            }
        }
        else {
            resTerm.append("(" + arg1);

            int len = f.listLength();
            for (int i = 2; i < len; i++) {
                Formula fi = new Formula();
                fi.read(f.getArgument(i));
                String argi = toTHF1(fi,argsTp,relTpInfo);
                resTerm.append(" " + op_thf + " " + argi);
            }
        }

        resTerm.append(")");

        terms.put(resTerm.toString(),goalTp);
        return  resTerm.toString();
    }


    /** ***************************************************************
     * A help function for toTHF2; this help function addresses the THF
     * conversion of formulas with logical or arithmetic connective
     * at head position.
     *
     * @param f is the KIF formula to convert
     *
     * @param op_thf is the THF connective to use at head postion
     *
     * @param goalTp is the THF type suggested for this formula
     *
     * @param  argsTp is the THF type suggested for the arguments
     *
     * @param preferPrefix signals if prefix or infix conversion is preferred
     *
     */
    private String toTHFHelp2 (Formula f, String op_sumo, String op_thf, String goalTp, String argsTp, boolean preferPrefix) {

        THFdebugOut("\n  Enter toTHFHelp2: " + f.theFormula);

        // in toTHF2 and in this help function we always reconstruct the worked off 
        // formula (possible slightly modify it thereby) for later reuse 
        kifFormula.append("("+ op_sumo);

        // a string builder for the result
        StringBuilder resTerm = new StringBuilder();
        Formula f1 = new Formula();
        f1.read(f.getArgument(1));

        kifFormula.append(" ");	 

        // a (recursive) call to toTHF2 for the first argument
        String arg1 = toTHF2(f1);

        // we work off the remaining arguments and distinguish thereby between the prefix and infix case
        if (preferPrefix) {
            resTerm.append("(" + op_thf + " @ " + arg1);

            int len = f.listLength();
            for (int i = 2; i < len; i++) {
                Formula fi = new Formula();
                fi.read(f.getArgument(i));
                kifFormula.append(" ");	 
                String argi = toTHF2(fi);
                resTerm.append(" @ " + argi);
            }
        }
        else {
            resTerm.append("(" + arg1);

            int len = f.listLength();
            for (int i = 2; i < len; i++) {
                Formula fi = new Formula();
                fi.read(f.getArgument(i));
                kifFormula.append(" ");	 
                String argi = toTHF2(fi);
                resTerm.append(" " + op_thf + " " + argi);
            }
        }

        resTerm.append(")");
        kifFormula.append(")");

        // we also remember the new type information we gained for the resulting term; this is
        // very important 
        terms.put(resTerm.toString(),goalTp);

        THFdebugOut("\n  Exit toTHFHelp2: " + f.theFormula);
        return 	resTerm.toString();
    }

    /** ***************************************************************
     * A help function for toTHF1; this help function addresses the THF
     * conversion of quantified formulas 
     *
     * @param f is the KIF formula to convert
     *
     * @param quant_thf is the THF quantifier to use at head postion
     *
     * @param relTpInfo is the passed on semantic 'type' information for symbols in f
     *
     */
    private String toTHFQuant1 (Formula f, String quant_thf, HashMap relTpInfo) {

        THFdebugOut("\n  Debug: universal quantifier at head position in " + f.theFormula);

        String varlist = f.getArgument(1);
        Formula varlistF = new Formula();
        varlistF.read(varlist);
        StringBuilder resTerm = new StringBuilder();
        resTerm.append("(" + quant_thf + " ["); 
        int len = varlistF.listLength();

        String arg2 = f.getArgument(2);
        Formula arg2F = new Formula();
        arg2F.read(arg2);

        for (int i = 0; i < len; i++) {
            String var = varlistF.getArgument(i);
            String varTHF = toTHFKifVar(var);
            terms.put(varTHF,unknownTp);
        }

        String arg2FTHF = toTHF1(arg2F,boolTp,relTpInfo);

        for (int i = 0; i < len; i++) {
            String var = varlistF.getArgument(i);
            String varTHF = toTHFKifVar(var);
            if (i < 1) {
                resTerm.append(varTHF + ": " + terms.get(varTHF));
            }
            else {
                resTerm.append("," + varTHF + ": " + terms.get(varTHF));
            }
        }

        resTerm.append("]: " + arg2FTHF + ")"); 
        terms.put(resTerm.toString(),boolTp);
        return resTerm.toString();
    }


    /** ***************************************************************
     * A help function for toTHF1; this help function addresses the THF
     * conversion of KappaFN formulas 
     *
     * @param f is the KIF formula to convert
     *
     * @param kappa_thf is the THF quantifier to use at head postion ("^")
     *
     * @param relTpInfo is the passed on semantic 'type' information for symbols in f
     *
     */
    private String toTHFKappaFN1 (Formula f, String kappa_thf, HashMap relTpInfo) {

        THFdebugOut("\n  Debug: KappaFn at head position in " + f.theFormula);

        StringBuilder resTerm = new StringBuilder();

        String var = f.getArgument(1);

        String varTHF = toTHFKifVar(var);
        terms.put(varTHF,unknownTp);

        String arg2 = f.getArgument(2);
        Formula arg2F = new Formula();
        arg2F.read(arg2);

        String arg2FTHF = toTHF1(arg2F,boolTp,relTpInfo);

        String varTHFtype = (String) terms.get(varTHF);

        resTerm.append("(" + kappa_thf + " [" + varTHF + ": " + varTHFtype + "]: " + arg2FTHF + ")"); 

        terms.put(resTerm.toString(),"(" + varTHFtype + typeDelimiter + boolTp + ")");

        return resTerm.toString();
    }


    /** ***************************************************************
     * A help function for toTHF2; this help function addresses the THF
     * conversion of quantified formulas 
     *
     * @param f is the KIF formula to convert
     *
     * @param quant_thf is the THF quantifier to use at head postion
     *
     */
    private String toTHFQuant2 (Formula f,String quant_sumo,String quant_thf) {

        THFdebugOut("\n  Debug: universal quantifier at head position in " + f.theFormula);

        String varlist = f.getArgument(1);

        kifFormula.append("("+ quant_sumo + " " + varlist + " ");

        Formula varlistF = new Formula();
        varlistF.read(varlist);
        StringBuilder resTerm = new StringBuilder();
        resTerm.append("(" + quant_thf + " ["); 

        int len = varlistF.listLength();

        String arg2 = f.getArgument(2);
        Formula arg2F = new Formula();
        arg2F.read(arg2);

        String arg2FTHF = toTHF2(arg2F);

        for (int i = 0; i < len; i++) {
            String var = varlistF.getArgument(i);
            String varTHF = toTHFKifVar(var);
            if (i < 1) {
                resTerm.append(varTHF + ": " + terms.get(varTHF));
            }
            else {
                resTerm.append("," + varTHF + ": " + terms.get(varTHF));
            }
        }

        resTerm.append("]: " + arg2FTHF + ")"); 
        kifFormula.append(")");
        terms.put(resTerm.toString(),boolTp);
        return resTerm.toString();
    }

    /** ***************************************************************
     * A help function for toTHF1; this help function addresses the THF
     * conversion of KappaFN formulas 
     *
     * @param f is the KIF formula to convert
     *
     * @param kappa_thf is the THF quantifier to use at head postion ("^")
     *
     * @param relTpInfo is the passed on semantic 'type' information for symbols in f
     *
     */
    private String toTHFKappaFN2 (Formula f, String kappa_sumo, String kappa_thf) {

        THFdebugOut("\n  Debug: KappaFn at head position in " + f.theFormula);

        StringBuilder resTerm = new StringBuilder();	

        String var = f.getArgument(1);

        kifFormula.append("("+ kappa_sumo + " " + var + " ");

        String varTHF = toTHFKifVar(var);
        terms.put(varTHF,unknownTp);

        String arg2 = f.getArgument(2);
        Formula arg2F = new Formula();
        arg2F.read(arg2);

        String arg2FTHF = toTHF2(arg2F);

        String varTHFtype = groundType("NOT_APPLICABLE",(String) terms.get(varTHF));

        resTerm.append("(" + kappa_thf + " [" + varTHF + ": " + varTHFtype + "]: " + arg2FTHF + ")"); 

        kifFormula.append(")");

        terms.put(resTerm.toString(),"(" + varTHFtype + typeDelimiter + boolTp + ")");
        return resTerm.toString();
    }

    /** ***************************************************************
     * A function that computes the arity of THF types
     *
     * @param thfTp is the THF type
     *
     */
    private int arity(String thfTp) { 

        THFdebugOut("\n   Enter arity with: " + thfTp);
        int res = 0;

        List help = toTHFList(thfTp);

        if (help.get(0) instanceof java.lang.String) {
            res = 0;
        }
        else {
            res = ((List) help.get(0)).size() - 1;
        }
        THFdebugOut("\n   Exit arity with: " + res);
        return res;
    }

    /** ***************************************************************
     * A help function that concatenates a string to the last string
     * argument of a list of strings
     *
     * @param str is the string to add
     *
     * @param accu is the list of strings
     */
    private List addStr(String str, List accu) {

        // THFdebugOut("\n   Enter addStr with: " + str + " " + accu);
        List reslist = new ArrayList();

        if (accu.isEmpty()) {
            reslist = Arrays.asList(str);
        }
        else {
            String laststr = (String) accu.get(accu.size() - 1);
            // THFdebugOut("\n   Inside addStr laststr = " + laststr);
            reslist = accu;
            reslist.set(reslist.size() - 1, (laststr + str));
            // THFdebugOut("\n   Inside addStr reslist = " + reslist.toString());
        }

        // THFdebugOut("\n   Exit addStr with: " + reslist.toString());	    
        return reslist;
    }

    /** ***************************************************************
     * A function that translates a THF type into a list of its subtypes
     * whereas the goaltype is put first into the result list;
     * e.g. "$i>($i>$i)>$o" is converted into ["$o","$i","($i>$)"]
     *
     * @param thfTp is the THF type to convert
     */
    private List toTHFList(String thfTp) {

        THFdebugOut("\n   Enter toTHFList with: " + thfTp);
        List res = null;
        List help = toTHFListH(thfTp,0,Arrays.asList());

        if (!thfTp.startsWith("(")) {
            res = help;
        }
        else if (help.size() == 1) {
            res = Arrays.asList(help);
        }
        else {
            String last = (String) help.get(help.size() - 1);
            help.remove(help.size() - 1);
            help.add(0,last);
            res = Arrays.asList(help);
        }

        THFdebugOut("\n   Exit toTHFList with: " + res.toString());
        return res; 
    }    

    /** ***************************************************************
     * A help function for toTHFList above; this is actually a little 
     * automaton that that needs to correctly parse bracketed THF type 
     * strings
     *
     * @param thfTp is the THF type 
     *
     * @param i is an integer to count open brackets
     *
     * @param accu is accumulator in which the parsed information is passed on
     *
     */
    private List toTHFListH(String thfTp, int i, List accu) {


        THFdebugOut("\n   Enter toTHFListH with: " + thfTp + " " + i + " " + accu.toString());
        List reslist = new ArrayList();

        // thfTp is base type
        if (i == 0) {
            if (thfTp.equals("")) {
                reslist = accu;
            }
            else if (thfTp.equals(indTp)) {
                reslist.add(indTp);
            }
            else if (thfTp.equals(boolTp)) {
                reslist.add(boolTp);
            }
            else if (thfTp.equals(unknownTp)) {
                reslist.add(unknownTp);
            }
            else if (thfTp.equals(problemTp)) {
                reslist.add(problemTp);
            }
            // in all other case thfTp must be of form (tp1 > ... > tpn)
            else if (thfTp.startsWith("(")) {
                reslist = toTHFListH(thfTp.substring(1),1,accu);
            }
            // there is no other case
            else {
                reslist.add("something_went_wrong_0");
            }
        }
        else if (i == 1) {
            if (thfTp.startsWith("(")) {
                reslist = toTHFListH(thfTp.substring(1),2,addStr("(",accu));
            }
            else if (thfTp.startsWith(")")) {
                reslist = toTHFListH(thfTp.substring(1),0,accu);
            }
            else if (thfTp.startsWith(">")) {
                List helplist = new ArrayList();
                helplist.addAll(accu);
                helplist.add("");
                reslist = toTHFListH(thfTp.substring(1),1,helplist);
            }
            else {
                Pattern p = Pattern.compile("([a-zA-Z$]?).*");
                Matcher m = p.matcher(thfTp);
                boolean b = m.matches();

                if (b) {
                    String mstr = m.group(1);
                    reslist = toTHFListH(thfTp.substring(1),1,addStr(mstr,accu));
                }
                else {
                    reslist.add("something_went_wrong_1");
                }
            }
        }
        else if (i > 1) {
            if (thfTp.startsWith("(")) {
                reslist = toTHFListH(thfTp.substring(1),i + 1,addStr("(",accu));
            }
            else if (thfTp.startsWith(")")) {
                reslist = toTHFListH(thfTp.substring(1),i - 1,addStr(")",accu));
            }
            else {
                Pattern p = Pattern.compile("([a-zA-Z$>]?).*");
                Matcher m = p.matcher(thfTp);
                boolean b = m.matches();

                if (b) {
                    String mstr = m.group(1);
                    reslist = toTHFListH(thfTp.substring(1),i,addStr(mstr,accu));
                }
                else {
                    reslist.add("something_went_wrong_>1");
                }
            }
        }

        THFdebugOut("\n   Exit toTHFListH with: " + reslist);
        return reslist;	
    }

    /** ***************************************************************
     * A function that creates a function type over unknownTp with 
     * specified arity
     * e.g. for int 3 it computes "uknownTp>uknownTp>uknownTp" 
     *
     * @param int is the requested arity 
     */
    private String makeUnknownTp (int num) {
        StringBuilder result = new StringBuilder();
        if (num == 1) {
            result.append(unknownTp);
        }
        else { 
            result.append("(" + unknownTp);
            for (int i = 2; i <= num; i++) {
                result.append(typeDelimiter + unknownTp);
            }
            result.append(")");
        }
        return result.toString();
    }

    /** ***************************************************************
     * A function that computes a new 'compromise' type for two conflicting 
     * type informations for one and the same THF term
     *
     * @param type1 is the first given type
     *
     * @param type2 is the second given type
     *
     */	
    private String computeConflictType(String type1, String type2) {

        THFdebugOut("\n Enter computeConflictType t1= " + type1 + "  t2= "+ type2); 
        String res = null;

        if (type1.equals(unknownTp)) {
            res = type2;
        }
        else if (type2.equals(unknownTp)) {
            res = type1;
        }
        else {
            int a1 = arity(type1);
            int a2 = arity(type2);
            int max = 0;
            if (a1 < a2) {
                max = a2;
            }
            else {
                max = a1;
            }
            res = makeUnknownTp(max + 1);
        }

        THFdebugOut("\n Exit computeConflictType t= " + res);
        return res;
    }

    /** ***************************************************************
     * A predicate that checks whether a a term-to-type map contains some
     * 'useful' information for a given symbol
     *
     * @param map is term-to-type map
     *
     * @param sym is the symbol (or term) to look for
     *
     */	
    private boolean containsRelevantTypeInfo(HashMap map,String sym) {

        boolean result = false;

        // the criterion is that map returns a type information list for sym
        // which has at least one non null-entry (otherwise there is no useful information
        // given and the predicate returns false
        if (map.containsKey(sym)) {
            List l = (List) map.get(sym); 
            for (Iterator it = l.iterator(); it.hasNext();) {
                String entry = (String) it.next();
                if (!(entry == null)) {
                    result = true;
                }
            }
        }

        return result;
    }


    /** ***************************************************************
     * A recursive function that turns a SUMO formula into a THF representation
     * which may still contain occurrences of the 'unknownTp'
     *
     * @param f is a KIF formula to convert into THF format
     *
     * @param type is a suggested THF type for f
     *
     * @param relTpInfo is the passed on semantic 'type' information for symbols in f
     *
     */
    private String toTHF1(Formula f, String type, HashMap relTpInfo) {

        StringBuilder result = new StringBuilder();

        //boolean THFdebugOld = THFdebug;
        //THFdebug = true;
        THFdebugOut("\n Enter toTHF1\n    f=" + f.theFormula + ",\n    type=" + type + ",\n    relTpInfo" + relTpInfo.toString() + "\n    terms=" + terms.toString() + "\n    localsig=" + localsig.toString() +  "\n    overallsig=" + overallsig.toString());
        //THFdebug = THFdebugOld;

        if (!f.listP()) {
            String sym = f.theFormula;

            /* sym might be logical connective TRUE */
            if (sym.equals(Formula.LOG_TRUE)) { 
                THFdebugOut("\n  Debug: " + sym + " equals LOG_TRUE");
                result.append("$true");
                terms.put("$true",boolTp);

            }
            /* sym might be logical connective FALSE */
            else if (sym.equals(Formula.LOG_FALSE)) { 
                THFdebugOut("\n  Debug: " + sym + " equals LOG_FALSE");
                result.append("$false"); 
                terms.put("$false",boolTp);
            }

            /* sym is a Kif variable */
            else if (isKifVar(sym)) { 
                String symcon = toTHFKifVar(sym);
                if ((!terms.containsKey(symcon) && !type.equals(unknownTp)) ||
                        (terms.containsKey(symcon) && terms.get(symcon).equals(unknownTp))) {
                    terms.put(symcon,type);
                }		   
                result.append(symcon);
            }

            /* sym is a constant symbol */
            else {
                String symcon = toTHFKifConst(sym);

                /* sym is a constant symbol with type 'unknownTp'; type maybe overwritten */
                if (terms.containsKey(symcon) && unknownTp.equals(terms.get(symcon))) {
                    localsig.put(symcon,type);
                    terms.put(symcon,type);
                }
                /* sym is a constant symbol with defined type that is different to the argument type */
                else if (terms.containsKey(symcon) && !type.equals(terms.get(symcon))) {
                    String newTp = computeConflictType(type,(String) terms.get(symcon));
                    localsig.put(symcon,newTp);
                    terms.put(symcon,newTp);
                    THFdebugOut("\n  Debug:  type inconsistency detected (constants): " + sym + "-->" + symcon + ": " + localsig.get(symcon) + " vs. " + type + " Will use new type " + newTp);
                }
                /* sym must be a constant symbol whose type needs to be defined as type */
                else {
                    THFdebugOut("\n  Debug: " + sym + " must be a constant symbol whose type needs to be defined as  " + type);
                    localsig.put(symcon,type);
                    terms.put(symcon,type); 
                }
                result.append(symcon);
            }
        }

        /* the empty list should not be occuring */
        else if (f.empty()) {
            THFdebugOut("\n  Debug: something went wrong; empty formula: " + f.theFormula);
            result.append("something_went_wrong");
        }

        /* double bracketed formula or bracketed Boolean constant */
        else if (Formula.listP(f.car()) || (f.listLength() == 1)) {
            THFdebugOut("\n  Debug: double bracketed formula or bracketed Boolean constant" + f.theFormula);
            String arg1 = f.car();
            Formula arg1F = new Formula();
            arg1F.read(arg1);
            String arg1FTHF = toTHF1(arg1F,boolTp,relTpInfo);
            result.append(arg1FTHF);
            terms.put(f.theFormula,boolTp);
        }

        /* the formula has form (h arg1 ... argN) */	    
        else {
            String h = f.getArgument(0);
            /* documentation formulas and some others are not translated */
            if (h.equals("documentation") || h.equals("document")  || h.equals("synonymousExternalConcept") || h.equals("termFormat") || h.equals("names") || h.equals("abbreviation") || h.equals("format") || h.equals("comment") || h.equals("conventionalShortName") || h.equals("externalImage") || h.equals("canonicalPlaceName") || h.equals("government") || h.equals("formerName") || h.equals("conventionalLongName") || h.equals("conventionalShortName") || h.equals("relatedExternalConcept") || h.equals("localLongName") || h.equals("localShortName") || h.equals("codeName") || h.equals("givenName") || h.equals("lexicon") || h.equals("abbrev") || h.equals("carCode") || h.equals("governmentType") || h.equals("established") || h.equals("codeMapping") || h.equals("acronym")) {
                result.append(notTranslatedStr + f.theFormula.trim());
            }
            /* we treat the cases where h is a logical or arithmetic connective */
            else if (h.equals(Formula.NOT)) {
                result.append(toTHFHelp1(f,"~",boolTp,boolTp,true,relTpInfo));
            }
            else if (h.equals(Formula.AND)) {
                result.append(toTHFHelp1(f,"&",boolTp,boolTp,false,relTpInfo));
            }
            else if (h.equals(Formula.OR)) {
                result.append(toTHFHelp1(f,"|",boolTp,boolTp,false,relTpInfo));
            }
            else if (h.equals(Formula.IF)) { 
                result.append(toTHFHelp1(f,"=>",boolTp,boolTp,false,relTpInfo));
            }
            else if (h.equals(Formula.IFF)) { 
                result.append(toTHFHelp1(f,"<=>",boolTp,boolTp,false,relTpInfo));
            }
            else if (h.equals(Formula.EQUAL)) { 
                String arg1 = f.getArgument(1);
                Formula arg1F = new Formula();
                arg1F.read(arg1);
                String arg1FTHF = toTHF1(arg1F,unknownTp,relTpInfo);
                String arg1Tp = (String) terms.get(arg1FTHF);
                String arg2 = f.getArgument(2);
                Formula arg2F = new Formula();
                arg1F.read(arg2);
                String arg2FTHF = toTHF1(arg1F,unknownTp,relTpInfo);
                String arg2Tp = (String) terms.get(arg2FTHF);
                String consensType = arg1Tp;
                if (!arg1Tp.equals(arg2Tp)) {
                    consensType = computeConflictType(arg1Tp,arg2Tp);
                }
                result.append(toTHFHelp1(f,"=",boolTp,consensType,false,relTpInfo));
            }
            else if (h.equals(Formula.GT)) {
                result.append(toTHFHelp1(f,"gt",boolTp,indTp,true,relTpInfo));
            }
            else if (h.equals(Formula.GTET)) { 
                result.append(toTHFHelp1(f,"gtet",boolTp,indTp,true,relTpInfo));
            }
            else if (h.equals(Formula.LT))  { 
                result.append(toTHFHelp1(f,"lt",boolTp,indTp,true,relTpInfo));
            }
            else if (h.equals(Formula.LTET)) { 
                result.append(toTHFHelp1(f,"ltet",boolTp,indTp,true,relTpInfo));
            }
            else if (h.equals(Formula.PLUSFN)) {
                result.append(toTHFHelp1(f,"plus",indTp,indTp,true,relTpInfo));
            }  
            else if (h.equals(Formula.MINUSFN)) { 
                result.append(toTHFHelp1(f,"minus",indTp,indTp,true,relTpInfo));
            }  
            else if (h.equals(Formula.TIMESFN)) { 
                result.append(toTHFHelp1(f,"times",indTp,indTp,true,relTpInfo));
            }  
            else if (h.equals(Formula.DIVIDEFN)) { 
                result.append(toTHFHelp1(f,"div",indTp,indTp,true,relTpInfo));
            }

            /* we treat the cases where h is a quantifier */
            else if (h.equals(Formula.UQUANT)) { 
                result.append(toTHFQuant1(f,"!",relTpInfo));
            }
            else if (h.equals(Formula.EQUANT)) {
                result.append(toTHFQuant1(f,"?",relTpInfo));
            }

            /* we treat the case where h is the KappaFN */
            else if (h.equals(Formula.KAPPAFN)) {
                result.append(toTHFKappaFN1(f,"^",relTpInfo));
                // old: 
                //THFdebugOut("\n  Debug: kappa function at head position in " + f.theFormula);
                //String res = "kappaFn_todo";
                //localsig.put(res,type);
                //terms.put(res,type);
                //result.append(res);
            }

            /* now h must be some non-logical symbol h with arguments arg1 ... argN */
            else {
                THFdebugOut("\n  Debug: non-logical head position in " + f.theFormula);
                StringBuilder resTerm = new StringBuilder();
                StringBuilder resType = new StringBuilder();

                String hconv = null;
                if (isKifVar(h)) {
                    hconv = toTHFKifVar(h);
                }
                else {
                    hconv = toTHFKifConst(h);
                }

                resTerm.append("(" + hconv);
                resType.append("("); 

                int len = f.listLength();

                List typeInfo = new ArrayList<String>();
                String goalTp = null;


                // relTpInfo, that is the KB, contains some useful type information;
                // store it in variables typeInfo and goalTp 
                if (containsRelevantTypeInfo(relTpInfo,h)) {
                    typeInfo = (List<String>) relTpInfo.get(h); 
                    THFdebugOut("\n   relTpInfo contains  " + hconv + " with " + typeInfo.toString());
                    if (typeInfo.get(0) == null) {
                        goalTp = boolTp;
                    }
                    else {
                        String sumoTp = (String) typeInfo.get(0);
                        goalTp = KIFType2THF(sumoTp);
                    }

                }
                // the terms-to-type mapping contains useful information on hconv; 
                // store it in variables typeInfo and goalTp 
                else if (terms.containsKey(hconv) && !((terms.get(hconv)).equals(unknownTp))) {
                    THFdebugOut("\n   terms contains  " + hconv + " and it is not unknownTp");
                    List typeInfoHelp = (toTHFList((String) terms.get(hconv)));
                    if (len != typeInfoHelp.size()) {
                        for (int i = 0; i < len; i++) {
                            typeInfo.add(unknownTp);
                            if (type.equals(unknownTp)) {
                                goalTp = unknownTp;
                            }
                            else {
                                goalTp = type;
                            }
                        }
                    }
                    else {
                        typeInfo = typeInfoHelp;
                        goalTp = (String) typeInfo.get(0);
                    }
                }
                // no useful information is available; translation proceeds purely syntactic;
                // store information in variables typeInfo and goalTp 
                else {
                    THFdebugOut("\n   Neither relTpInfo nor terms contains  " + hconv + " or it is unknownTp; len=" + len);
                    for (int i = 0; i < len; i++) {
                        typeInfo.add(unknownTp);
                        if (type.equals(unknownTp)) {
                            goalTp = unknownTp;
                        }
                        else {
                            goalTp = type;
                        }
                    }
                }

                THFdebugOut("\n   typeInfo =  " + typeInfo.toString());

                // recurse over the arguments and pass on useful type information; memorize useful information delivered back 
                // bottom up
                for (int i = 1; i < Math.min(len,7); i++) {
                    String sumoTp = (String) typeInfo.get(i);
                    String argiTp = KIFType2THF(sumoTp);
                    String argi = (f.getArgument(i));
                    Formula argiF = new Formula();
                    argiF.read(argi);
                    String argiFTHF = toTHF1(argiF,argiTp,relTpInfo);
                    resTerm.append(" @ " + argiFTHF);
                    if (!argiTp.equals(unknownTp)) {
                        resType.append(argiTp + typeDelimiter);
                        terms.put(argiFTHF,argiTp);
                    }
                    else {
                        resType.append(terms.get(argiFTHF) + typeDelimiter);
                        terms.put(argiFTHF,terms.get(argiFTHF));
                    }
                }

                // use the freshly computed type information to (re-)declare the type information for the head symbol hconv
                resTerm.append(")");
                resType.append(goalTp + ")");

                THFdebugOut("\n   Debug: declaring: " + hconv + " of type " + resType.toString());
                terms.put(hconv,resType.toString());
                if (!isKifVar(h)) {
                    localsig.put(hconv,resType.toString());
                }
                terms.put(resTerm.toString(),goalTp);
                result.append(resTerm.toString());
            }
        }

        THFdebugOut("\n Exit toTHF1\n    result=" + result.toString() + ",\n    relTpInfo" + relTpInfo.toString() + "\n    terms=" + terms.toString() + "\n    localsig=" + localsig.toString() +  "\n    overallsig=" + overallsig.toString());

        return result.toString();
    }

    /** ***************************************************************
     * A function that translates semantic type information from the KB
     * as maintained in relTpInfo and translates into a THF type string
     *
     * @param o is a KIF 'type' as string or a list of KIF type strings
     *
     */
    private String toTHFTp (Object o) {

        THFdebugOut("\n   Enter toTHFTp with " + o.toString());

        String res = null;
        if (o instanceof java.lang.String) {
            res = (String) o;
        }
        else if (o instanceof java.util.List) {
            res = toTHFTpList((List) o);
        }

        THFdebugOut("\n   Exit toTHFTp with " + res.toString());
        return res;
    }


    /** ***************************************************************
     * A help function for toTHFTp
     *
     * @param l is a list of KIF type strings
     *
     */
    private String toTHFTpList (List l) {

        THFdebugOut("\n   Enter toTHFTpList with " + l.toString());
        StringBuilder result = new StringBuilder();
        for (int i = 1; i < l.size(); i++) {
            Object entry = l.get(i);
            if (entry instanceof java.lang.String) {
                result.append((String) entry + typeDelimiter);
            }
            else if (entry instanceof java.util.List) {	   
                result.append("(" + toTHFTpList((List) entry) + ")");
            }
        }

        Object entry0 = l.get(0);
        if (entry0 instanceof java.lang.String) {
            result.append((String) entry0);
        }
        else if (entry0 instanceof java.util.List) {	  
            result.append("(" + toTHFTpList((List) entry0) + ")");
        }

        THFdebugOut("\n   Exit toTHFTpList with " + result.toString());
        return result.toString();
    }

    /** ***************************************************************
     * A function that computes a suffix for a THF constant name that
     * suitably encodes some given THF type information (one problem is 
     * that '$' is not allowed in THF constant names).
     *
     * @param thfTp is the THF type to encode
     *
     */
    private String toTHFSuffix (String thfTp) {

        THFdebugOut("\n   Enter toTHFSuffix with " + thfTp);
        String result = thfTp;
        result = result.replaceAll("\\(","I");
        result = result.replaceAll("\\)","I");
        result = result.replaceAll("\\$","");
        result = result.replaceAll("\\>","");
        THFdebugOut("\n   Exit toTHFSuffix with " + result);
        return result;
    }

    /** ***************************************************************
     * A function that computes a new name for a given constant name.
     * It computes and appends a suffix for the constant name that
     * suitably encodes some given THF type information (one problem is 
     * that '$' is not allowed in THF constant names).
     *
     * @param oldconst is the name of the given constant
     *
     * @param thfTp is the THF type to encode
     *
     */
    private String  makeNewConstWithSuffix(String oldConst, String thfTp) {

        THFdebugOut("\n   Enter makeNewConstWithSuffix with oldconst " + oldConst + " and thfTp " + thfTp);
        String delimiter = "_THFTYPE_";
        String suffix = toTHFSuffix(thfTp);
        String oldConstPrefix = oldConst;

        if (oldConst.contains(delimiter)) {
            oldConstPrefix = (oldConst.split(delimiter))[0];
        }

        String res = oldConstPrefix + delimiter + suffix;

        THFdebugOut("\n   Exit makeNewConstWithSuffix with " + res);
        return res;
    }



    /** ***************************************************************
     * A recursive function that turns a SUMO formula into a THF string.
     * It works structurally similar to toTHF1 but it cares about the 
     * 'unknownTp' information leftover by toTHF1; several calls to THF2
     * may be required until sufficient type information is generated and
     * all 'unknownTp' entries have dissappeared
     *
     * @param f A formula to convert into THF format
     *
     */
    private String toTHF2(Formula f) {

        StringBuilder result = new StringBuilder();
        THFdebugOut("\n Enter toTHF2\n    f=" + f.theFormula + "\n    terms=" + terms.toString() + "\n    localsig=" + localsig.toString() +  "\n    overallsig=" + overallsig.toString() + "\n    kifFormula=" + kifFormula.toString());

        if (!f.listP()) {
            String sym = f.theFormula;

            /* sym might be logical connective TRUE */
            if (sym.equals(Formula.LOG_TRUE)) { 
                result.append("$true"); 
                kifFormula.append(Formula.LOG_TRUE);
            }
            /* sym might be logical connective FALSE */
            else if (sym.equals(Formula.LOG_FALSE)) { 
                result.append("$false"); 
                kifFormula.append(Formula.LOG_FALSE);
            }

            /* sym is a Kif variable */
            else if (isKifVar(sym)) { 
                String symcon = toTHFKifVar(sym);
                //if (terms.get(symcon).equals(unknownTp)) {
                //   terms.put(symcon,indTp);
                //}
                String nwTp = groundType(symcon,(String) terms.get(symcon));
                terms.put(symcon,nwTp);
                result.append(symcon);
                kifFormula.append(sym);
            }

            /* sym is a constant symbol */
            else {
                String symcon = toTHFKifConst(sym);
                if (terms.get(symcon) == null) {
		    terms.put(symcon,indTp);
		    localsig.put(symcon,indTp);
                }
                String nwTp = groundType(symcon,(String) terms.get(symcon));
                symcon = makeNewConstWithSuffix(symcon,nwTp);
                terms.put(symcon,nwTp);
                localsig.put(symcon,nwTp);
                result.append(symcon);
                kifFormula.append(symcon);
            }
        }

        /* the empty list should not be occuring */
        else if (f.empty()) {
            result.append("something_went_wrong");
            kifFormula.append("something_went_wrong");
        }

        /* double bracketed formula or bracketed Boolean constant */
        else if (Formula.listP(f.car()) || (f.listLength() == 1)) {
            String arg1 = f.car();
            Formula arg1F = new Formula();
            arg1F.read(arg1);
            String arg1FTHF = toTHF2(arg1F);
            result.append(arg1FTHF); 
        }

        /* the formula has form (h arg1 ... argN) */	    
        else {
            String h = f.getArgument(0);
            String arith_pred_tp = "(" + indTp + typeDelimiter + indTp + typeDelimiter + boolTp + ")";
            String arith_op_tp = "(" + indTp + typeDelimiter + indTp + typeDelimiter + indTp + ")";

            /* documentation formulas are not translated */
            if (h.equals("documentation")  || h.equals("document")  || h.equals("synonymousExternalConcept") || h.equals("termFormat") || h.equals("names") || h.equals("abbreviation")  || h.equals("format") || h.equals("comment")  || h.equals("conventionalShortName") || h.equals("externalImage") || h.equals("canonicalPlaceName") || h.equals("government") || h.equals("formerName") || h.equals("conventionalLongName") || h.equals("conventionalShortName") || h.equals("relatedExternalConcept") || h.equals("localLongName") || h.equals("localShortName") || h.equals("codeName") || h.equals("givenName") || h.equals("lexicon") || h.equals("abbrev") || h.equals("carCode") || h.equals("governmentType") || h.equals("established") || h.equals("codeMapping") || h.equals("acronym")) {
                result.append(notTranslatedStr + f.theFormula.trim());
            }
            /* we treat the cases where h is a logical or arithmetic connective */
            else if (h.equals(Formula.NOT)) {
                result.append(toTHFHelp2(f,Formula.NOT,"~",boolTp,boolTp,true));
            }
            else if (h.equals(Formula.AND)) {
                result.append(toTHFHelp2(f,Formula.AND,"&",boolTp,boolTp,false));
            }
            else if (h.equals(Formula.OR)) {
                result.append(toTHFHelp2(f,Formula.OR,"|",boolTp,boolTp,false));
            }
            else if (h.equals(Formula.IF)) { 
                result.append(toTHFHelp2(f,Formula.IF,"=>",boolTp,boolTp,false));
            }
            else if (h.equals(Formula.IFF)) { 
                result.append(toTHFHelp2(f,Formula.IFF,"<=>",boolTp,boolTp,false));
            }
            else if (h.equals(Formula.EQUAL)) { 
                result.append(toTHFHelp2(f,Formula.EQUAL,"=",boolTp,unknownTp,false));
            }
            else if (h.equals(Formula.GT)) {
                String newHd = makeNewConstWithSuffix("gt",arith_pred_tp);
                result.append(toTHFHelp2(f,Formula.GT,newHd,boolTp,indTp,true));
                localsig.put(newHd,arith_pred_tp);
            }
            else if (h.equals(Formula.GTET)) { 
                String newHd = makeNewConstWithSuffix("gtet",arith_pred_tp);
                result.append(toTHFHelp2(f,Formula.GTET,newHd,boolTp,indTp,true));
                localsig.put(newHd,arith_pred_tp);
            }
            else if (h.equals(Formula.LT))  { 
                String newHd = makeNewConstWithSuffix("lt",arith_pred_tp);
                result.append(toTHFHelp2(f,Formula.LT,newHd,boolTp,indTp,true));
                localsig.put(newHd,arith_pred_tp);
            }
            else if (h.equals(Formula.LTET)) { 
                String newHd = makeNewConstWithSuffix("ltet",arith_pred_tp);
                result.append(toTHFHelp2(f,Formula.LTET,newHd,boolTp,indTp,true));
                localsig.put(newHd,arith_pred_tp);
            }
            else if (h.equals(Formula.PLUSFN)) {
                String newHd = makeNewConstWithSuffix("plus",arith_op_tp);
                result.append(toTHFHelp2(f,Formula.PLUSFN,newHd,indTp,indTp,true));
                localsig.put(newHd,arith_op_tp);
            }  
            else if (h.equals(Formula.MINUSFN)) { 
                String newHd = makeNewConstWithSuffix("minus",arith_op_tp);
                result.append(toTHFHelp2(f,Formula.MINUSFN,newHd,indTp,indTp,true));
                localsig.put(newHd,arith_op_tp);
            }  
            else if (h.equals(Formula.TIMESFN)) { 
                String newHd = makeNewConstWithSuffix("times",arith_op_tp);
                result.append(toTHFHelp2(f,Formula.TIMESFN,newHd,indTp,indTp,true));
                localsig.put(newHd,arith_op_tp);
            }  
            else if (h.equals(Formula.DIVIDEFN)) { 
                String newHd = makeNewConstWithSuffix("div",arith_op_tp);
                result.append(toTHFHelp2(f,Formula.DIVIDEFN,newHd,indTp,indTp,true));
                localsig.put(newHd,arith_op_tp);
            }
            else if (h.equals(Formula.UQUANT)) { 
                result.append(toTHFQuant2(f,Formula.UQUANT,"!"));
            }
            else if (h.equals(Formula.EQUANT)) {
                result.append(toTHFQuant2(f,Formula.EQUANT,"?"));
            }

            /* we treat the case where h is the KappaFN */
            else if (h.equals(Formula.KAPPAFN)) {
                result.append(toTHFKappaFN2(f,Formula.KAPPAFN,"^"));
                // old:
                // String res = "kappaFn_todo";
                // result.append(res);
                // kifFormula.append(res);
            }

            /* now h must be some non-logical symbol h with arguments arg1 ... argN */
            else {
                THFdebugOut("\n  Debug: non-logical head position in " + f.theFormula);
                StringBuilder resTerm = new StringBuilder();
                StringBuilder resType = new StringBuilder();

                String hconv = null;
                if (isKifVar(h)) {
                    hconv = toTHFKifVar(h);
                }
                else {
                    hconv = toTHFKifConst(h);
                }

                resTerm.append("(");
                kifFormula.append("(");
                int marker1 = kifFormula.length();
                resType.append("("); 

                int len = f.listLength();

                String headTpOld = (String) terms.get(hconv);
                List typeInfo = new ArrayList();
                if (isBaseTp(headTpOld)) {
                    typeInfo = Arrays.asList(makeUnknownTp(len));
                }
                else {
                    typeInfo = (List) toTHFList(headTpOld).get(0);
                }

                for (int i = 1; i < len; i++) {
                    String suggArgiTp = unknownTp;
                    if (i < typeInfo.size()) {
                        suggArgiTp = toTHFTp(typeInfo.get(i));
                    }
                    String argi = (f.getArgument(i));
                    Formula argiF = new Formula();
                    argiF.read(argi);
                    kifFormula.append(" ");
                    // String argiFTHF = "";
                    // if (isKifVar(argi) || isKifConst(argi) {
                    //    argiFTHF = toTHFKifVar(argi);
                    //    terms.put(argiFTHF,groundType(terms.get(argiFTHF)));
                    //}
                    //else if (isKifConst(argi)) {
                    //    argiFTHF = toTHFKifConst(argi);
                    //    terms.put(argiFTHF,groundType(terms.get(argiFTHF)));
                    //}
                    //else {

                    String argiFTHF = toTHF2(argiF);

                    //}
                    resTerm.append(" @ " + argiFTHF);
                    resType.append(terms.get(argiFTHF) + typeDelimiter);
                }

                String goalTp = groundType("NOT_APPLICABLE",toTHFTp(typeInfo.get(0)));    
                if (goalTp.equals(unknownTp)) {
                    goalTp = indTp;
                }

                resType.append(goalTp + ")");


                String headNew = null;
                String headNewKif = null;

                if (isKifVar(h)) {
                    headNew = hconv;
                    headNewKif = h;
                }
                else {
                    //if  (headTpOld.equals(resType.toString())) {
                    //    headNew = hconv; 
                    //    headNewKif = toTHFKifConst(h); 
                    //}
                    //else {
                    headNew = makeNewConstWithSuffix(hconv,resType.toString());
                    headNewKif = makeNewConstWithSuffix(toTHFKifConst(h),resType.toString());
                    subst.put(hconv,headNew);
                    //}
                }

                resTerm.insert(1,headNew);
                resTerm.append(")");
                kifFormula.insert(marker1,headNewKif);
                kifFormula.append(")");

                THFdebugOut("\n   Debug: declaring: " + headNew + " of type " + resType.toString());
                terms.put(headNew,resType.toString());
                if (!isKifVar(h)) {
                    localsig.put(headNew,resType.toString());
                }
                terms.put(resTerm.toString(),goalTp);
                result.append(resTerm.toString());
            }
        }

        THFdebugOut("\n Exit toTHF2\n    result=" + result.toString() + "\n    terms=" + terms.toString() + "\n    localsig=" + localsig.toString() +  "\n    overallsig=" + overallsig.toString() +  "\n    kifFormula=" + kifFormula.toString());

        return result.toString();
    }


    /** ***************************************************************
     * A method.that extracts the symbols in a formula that occur at function/predicate position and 
     * at argument position
     *
     * @param f A formula to investigate
     *
     */


    private List analyzeFormula (Formula form) {

        THFdebugOut("\n  Enter analyzeFormula with form=" + form);

        Set funcPosL = new HashSet();
        Set argPosL = new HashSet();
        List resL = new ArrayList();

        String formStr = form.theFormula.trim();

        if (isKifVar(formStr)) {
            resL.add(funcPosL);
            resL.add(argPosL);
        }
        else if (isKifConst(formStr)) {
            resL.add(funcPosL);
            argPosL.add(formStr);
            resL.add(argPosL);
        }
        else if (form.listP()) {
            String h = form.getArgument(0);
            if (h.equals(Formula.UQUANT) || h.equals(Formula.EQUANT) || h.equals(Formula.KAPPAFN)) { 
                Formula argF = new Formula();
                argF.read(form.getArgument(2));
                resL = analyzeFormula(argF);
            }
            else {
                if (isKifConst(h)) {
                    funcPosL.add(h);
                }

                for (int i = 1; i < form.listLength(); i++) {
                    String argStr = form.getArgument(i);
                    Formula argF = new Formula();
                    argF.read(argStr);
                    List recResultL = analyzeFormula(argF);
                    funcPosL.addAll((Set) recResultL.get(0));
                    argPosL.addAll((Set) recResultL.get(1));
                }
                resL.add(funcPosL);
                resL.add(argPosL);
            }
        }

        THFdebugOut("\n  Exit analyzeFormula with resL=" + resL.toString());

        return resL;
    }

    private SortedSet sortFormulas (Collection formulas) {

        THFdebugOut("\n   Enter sortFormulas with " + formulas.toString());

        TreeSet orderedFormulas = new TreeSet(new Comparator() {
            public int compare(Object o1, Object o2){
                THFdebugOut("\n   enter compare with " + o1.toString() + " and " + o2.toString());
                int res = 0;
                Formula f1 = (Formula) o1;
                String f1head = f1.getArgument(0);
                Formula f2 = (Formula) o2;
                String f2head = f2.getArgument(0);
                if (f1head.equals("instance") ||
                        f1head.equals("subclass") ||
                        f1head.equals("domain") ||
                        f1head.equals("domainSubclass") ||
                        f1head.equals("range") ||
                        f1head.equals("rangeSubclass") ||
                        f1head.equals("subrelation")
                ) {
                    res = 1;
                }
                else if (f2head.equals("instance") ||
                        f2head.equals("subclass") ||
                        f2head.equals("domain") ||
                        f2head.equals("domainSubclass") ||
                        f2head.equals("range") ||
                        f2head.equals("rangeSubclass") ||
                        f2head.equals("subrelation")
                ) {
                    res = -1;
                }
                else if (Formula.isLogicalOperator(f1head) && !Formula.isLogicalOperator(f2head)) {
                    res = -1;
                }
                else if (!Formula.isLogicalOperator(f1head) && Formula.isLogicalOperator(f2head)) {
                    res = 1;
                }
                else {
                    List res1 = analyzeFormula(f1);
                    List res2 = analyzeFormula(f2);
                    Set funcConsts1 = (Set) res1.get(0);
                    Set argConsts1 = (Set) res1.get(1);
                    Set funcConsts2 = (Set) res2.get(0);
                    Set argConsts2 = (Set) res2.get(1);
                    Set intersection1 = new HashSet(argConsts1);
                    Set intersection2 = new HashSet(argConsts2);
                    intersection1.retainAll(funcConsts2);
                    intersection2.retainAll(funcConsts1);
                    // f1 contains argument constants which are function constants of f2 
                    if (!intersection1.isEmpty()) {
                        res = -1; 
                    }
                    // f2 contains argument constants which are function constants of f1
                    else if (!intersection2.isEmpty()) {
                        res = 1;
                    }		   
                    else {
                        res = -1; 
                    }
                }
                THFdebugOut("\n   exit compare with " + res);
                return res;
            }
        });

        orderedFormulas.addAll(formulas);

        //List resL = new ArrayList(orderedFormulas);

        SortedSet resL = orderedFormulas;

        THFdebugOut("\n   exit sortFormulas with " + resL.toString());

        return resL;
    }


    private SortedSet sortFormulas2 (Collection formulas) {

        System.out.println("\n   Enter sortFormulas with " + formulas.toString());
        THFdebugOut("\n   Enter sortFormulas with " + formulas.toString());

        SortedSet orderedFormulas = new TreeSet(new Comparator() {
            public int compare(Object o1, Object o2){
                Formula f1 = (Formula) o1;
                Formula f2 = (Formula) o2;
                String h1 = f1.getArgument(0);
                // String h2 = f2.getArgument(0);
                if (h1.equals("instance") || 
                        h1.equals("domain") || 
                        h1.equals("domainSubclass") ||
                        h1.equals("subrelation") ||
                        h1.equals("relatedInternalConcept") ||
                        h1.equals("relatedInternalConcept") ||
                        h1.equals("disjointRelation")
                ) {
                    return 1; 
                }
                else {
                    return -1;
                }
            }
        });

        orderedFormulas.addAll(formulas);

        SortedSet resL = orderedFormulas;

        System.out.println("\n   exit sortFormulas with " + resL.toString());
        THFdebugOut("\n   exit sortFormulas with " + resL.toString());

        return resL;
    }


    /** ***************************************************************
     * A test method.
     */
    public static void main(String[] args) {

        THF thf = new THF();
        KBmanager kbmgr = KBmanager.getMgr();
        kbmgr.initializeOnce();
        KB kb = kbmgr.getKB("SUMO");  


        THFdebug = false;  /* set this to true for lots of debug output */
        //THFdebug = true;  /* set this to true for lots of debug output */	

	
        List<Formula> testFormulas = new ArrayList<Formula>();
        List<Formula> emptyL = Collections.emptyList(); 
        Formula newF = null;
        String newFTHF = null;

        try {
             
	    System.out.println("\n\nTest on all KB kb content:");
	    Collection coll = Collections.EMPTY_LIST;
	    String kbAll2 = "";
	    kbAll2 = thf.KIF2THF(kb.formulaMap.values(),coll,kb); 
	    FileWriter fstream = new FileWriter("/tmp/kbAll2.p");
	    BufferedWriter out = new BufferedWriter(fstream);
	    out.write(kbAll2);
	    out.close();
	    System.out.println("\n\nResult written to file " + "/tmp/kbAll2.p");
            
	    /*
            System.out.println("\n\nTest on all KB kb content with SInE:");
            String kbFileName = args[0];
            String query = args[1];
            SInE sine = SInE.getNewInstance(kbFileName);	    
            Set<String> selectedFormulaStrings = sine.performSelection(query);
            //for (String form : selectedFormulas) 
            // System.out.println(form); 
            List<Formula> selectedFormulas = new ArrayList<Formula>();
            for (String str : selectedFormulaStrings) {
                Formula newForm = new Formula();
                newForm.read(str);
                selectedFormulas.add(newForm);
            }
            List<Formula> selectedQuery = new ArrayList<Formula>();
            Formula newQ = new Formula();
            newQ.read(query);
            selectedQuery.add(newQ);
            String kbAll = "";
            kbAll = thf.KIF2THF(selectedFormulas,selectedQuery,kb);
            System.out.println(kbAll); 
            fstream = new FileWriter("/tmp/kbAll.p");
            out = new BufferedWriter(fstream);
            out.write(kbAll);
            out.close();
            System.out.println("\n\nResult written to file " + "/tmp/kbAll.p");

            System.out.println("\n\naskLEO Test: result = \n" + kb.askLEO(query,30,1,"LeoGlobal"));

	    System.out.println(KBmanager.getMgr().getKBnames());
	    */
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }

    } // main

}  // Formula.java

