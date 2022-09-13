package peergos.server.corenode;

import peergos.shared.corenode.*;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.*;

/**
 * Encapsulates CoreNode username rules.
 *
 */
public final class UsernameValidator {

    private static final Pattern VALID_USERNAME = Pattern.compile(Usernames.REGEX);

    // These are for potential future interoperability and federation/bridging
    public static final Set<String> BANNED_USERNAMES =
            Stream.of("ipfs", "ipns", "root", "http", "https", "dns", "admin", "administrator", "support", "email", "mail", "www",
                    "web", "onion", "tls", "i2p", "ftp", "sftp", "file", "mailto", "wss", "xmpp", "ssh", "smtp", "imap",
                    "irc", "matrix", "twitter", "facebook", "instagram", "linkedin", "wechat", "tiktok", "reddit",
                    "snapchat", "qq", "whatsapp", "signal", "telegram", "matrix", "briar", "ssb", "mastodon",
                    "apple", "google", "pinterest",
                    "mls", "btc", "eth", "mnr", "zec", "friends", "followers", "username", "groups")
            .collect(Collectors.toSet());

    /** Username rules:
     * no _- at the end
     * allowed characters [a-z0-9_-]
     * no __ or -- or _- or -_ inside
     * no _- at the beginning
     * is 1-32 characters long
     * @param username
     * @return true iff username is a valid username.
     */
    public static boolean isValidUsername(String username) {
        return (VALID_USERNAME.matcher(username).find() && ! BANNED_USERNAMES.contains(username))
                || LEGACY_UNDERSCORE.contains(username);
    }

    public static final Set<String> LEGACY_UNDERSCORE = Stream.of(
            "federal_appeal","elle_esse","xzz_yassin","jonas_lindemann","nicolas_thill","yr_zr0","a_bennassar",
            "the_darktrancer","evans_luke","skater_welladay","abc_007","a_little","narcoleptic_snowman","faraz_55",
            "robert_forestell","dfc_test","ozzy_reijnaert","granite_zl","slash_27","caso_saphy","asc_fer41","tim_chen",
            "bengao_zhou","boy_witch","levanto_0","abis_biso","la_la_land","jeff_systemhouse","yier_fang","femi-s_1986",
            "0_0","jungle_adventure","afx_infamix","statik_ip","the_powerdrift_dabster","edrad_wolf","space_cat",
            "r3dpill_17","chaaava_sulcom","b_kirby_8","tech_digger","computer_killer_9_million","radek_nielek",
            "eara_test","sudo_scientist","troels_a","bkd_5","elvin_arrow","ap_java","mr_benjiworld","shiba_coin",
            "white_cashmere","steinar_ag","scott_davis","6_6","nathaniel_gray","thann_banis","jiang_wei","mike_gale",
            "uppercase_manager","jiang_ziya","winston_smith","tiny_fingers","jared_balser","bama_dan","serene_xp",
            "fallen_melon","void_tux","boogie6_6_6","l1la_siren3","iulia_radu","pagmupka_88pagmupka","just_kush",
            "nesh_collo","kibana_user","giannis_geroulis","magnetic_cactus","edvard_norton","mac_a","electro_cloud",
            "blue_100","alex_renn","tp_voyager","the_taco_truck","nil_float","nifty_nft","coco_carma","maya_n",
            "santhosh_reddy","vini_mendes","warriorsga_legis","player_sgs","abn_anik","iagon_test","soupe_cramee",
            "amir_99","haven_mobius","jackripper_1888","ankush_itsfoss","artz_sam","ernesto_fm","seh_zade","dh_gmbnrc",
            "nesh_kothari","s_k_2003","steph_girow","kalibyr_bbx","ameyyy_7303","john_betong","head_brother",
            "unknown_3301","varun_invent","max_mustermann7","md_parker","n_e_felibata","neo_sunny",
            "sankt-peterburg_2017","ky_hsiao","miklehey_717","jd_online","pradeep_07","ernesto_f_m","public_enemy",
            "capitan_bonaccia","sram_bot19","darth_vader","serg_w","k_engelstad","udon_zud","aksja_01_81","darth_malt",
            "assassin_navod","system_design_public_storage","bobfromaccounting_ut","kris_d",
            "systemdesign_publicstorage","alastor_dk9","nu_ll","monkey_maniu","rahul_kulkarni","m_name","nbk_name",
            "v_142857","prince_156","quantoo_antoo","emanuel_ekstrom5634","tax_protestor","vicky_bs","alex_stap",
            "btenors_storage","faustino_c","ace_crusader","1234567890-qwerty_456","gia_bass_provincia_cremona_it",
            "other_side2","arsalan_daneshvar","art_om","evg01_kurz","margal_user","mr_q","mr_qaz","orca_pg","account_2",
            "drew_meetingav_net","test_37","matcho_a","3_14zdec","test_36","account_1","mr_kovacs","trinity_21","cem_7",
            "i_come_from_the_sky","peergos_32p","toshie_yoshida","gimme_cherry","0_o","fxu_36","michael_philippone",
            "der_crazy","scope_recast","jost_burkardt","patchdrive_admin","patchbay_email","patchbay_boss",
            "element_mae","patchbay_elephant","elegant_whale","o_b_o","saskia_sielias","jmhlbcmtknu_omx","coyote_pinke",
            "time_spirit","black_tourmaline86","talus_sp1ke","mr_mike","x0e_e0x","salted_hashbrown","marchon_gmail_com",
            "wtop_eipp","automuse_amrita","little_cow_cat","gevorg_vardanyan","gift_park","yufeng_chin","jac_cos","g_c",
            "kpt_kloss","smtp_sage3","newmay_admin_shared","patchbaycardano_smtp","normcoin_newmay","bitter_sweet",
            "cguo_zz","ebaiy_trimline","pierre_gronau_ndaal_eu","nikola_tesla","fateev_so","di_rocha27"
    ).collect(Collectors.toSet());
}
