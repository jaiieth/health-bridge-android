import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

public class RenderMockups {
    static Map<String, Color> colors;
    static Graphics2D g;
    static void label(String text, int x, int y, int size, String role, boolean bold) {
        g.setColor(colors.get(role));
        g.setFont(new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, size));
        g.drawString(text, x, y);
    }
    static void card(int y, int height, String title) {
        g.setColor(colors.get("surface")); g.fillRoundRect(24, y, 432, height, 32, 32);
        g.setColor(colors.get("outline")); g.drawRoundRect(24, y, 432, height, 32, 32);
        label(title, 44, y + 36, 22, "text", true);
    }
    static void button(String text, int y, boolean disabled) {
        g.setColor(colors.get(disabled ? "disabled" : "primary"));
        g.fillRoundRect(44, y, 392, 52, 16, 16);
        label(text, 60, y + 33, 18, disabled ? "on_disabled" : "on_primary", true);
    }
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of(args.length == 0 ? "/home/ubuntu/scratch/phase23-logs/previews" : args[0]));
        for (String theme : new String[]{"light", "dark"}) {
            var path = Path.of("app/src/main/res", theme.equals("light") ? "values" : "values-night", "colors.xml");
            var nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile()).getElementsByTagName("color");
            colors = new HashMap<>();
            for (int i=0;i<nodes.getLength();i++) {
                var n=nodes.item(i);
                colors.put(n.getAttributes().getNamedItem("name").getNodeValue().substring(3), Color.decode(n.getTextContent().trim()));
            }
            var image = new BufferedImage(480, 1540, BufferedImage.TYPE_INT_RGB);
            g=image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(colors.get("background"));g.fillRect(0,0,480,1540);
            label("Health Bridge",24,52,32,"text",true);
            label("Your health data. Your destination.",24,84,18,"muted",false);
            label("DESIGN RENDER NOT DEVICE SCREENSHOT",24,112,14,"muted",false);
            card(136,382,"Sync control");
            label("Saved health consent: OFF",44,216,22,"on_disabled",true);
            g.setColor(colors.get("paused"));g.fillRect(46,237,16,16);
            label("Build gated",76,253,18,"paused",true);
            label("Health sync unavailable in this build -",44,278,16,"muted",false);
            label("resource safety pending",44,298,16,"muted",false);
            button("Health work unavailable",304,true);
            label("Progress ready. No health delivery progress.",44,389,18,"muted",false);
            label("Last successful health sync",44,435,18,"text",true);
            label("Never recorded",44,470,20,"muted",false);
            card(542,398,"Connection & setup");
            label("Destination · example.invalid",44,621,20,"text",true);
            label("Synthetic host. Full endpoint shown on edit.",44,652,17,"muted",false);
            button("Edit endpoint",672,false);
            label("Token not saved. Enter a token.",44,760,17,"muted",false);
            g.setColor(colors.get("outline"));g.drawRoundRect(44,780,392,52,16,16);
            label("New bearer token and HMAC key",58,813,16,"muted",false);
            button("Save settings",856,false);
            card(964,188,"Health access");
            label("0 of 6 read permissions granted",44,1046,18,"muted",false);
            button("Grant health permissions",1072,false);
            card(1176,218,"Test destination");
            label("Sends an empty batch. Does not enable sync.",44,1251,17,"muted",false);
            button("Send test",1272,true);
            label("No recorded test acknowledgement.",44,1360,17,"muted",false);
            label("Status examples · labels accompany every color",24,1434,16,"muted",false);
            label("Blocked · Fix setup or health access",24,1466,18,"blocked",true);
            label("Paused · Health delivery is contained",24,1496,18,"paused",true);
            label("Unknown · No acknowledgement",24,1526,18,"unknown",true);
            g.dispose();
            ImageIO.write(image,"png",Path.of(args.length == 0 ? "/home/ubuntu/scratch/phase23-logs/previews" : args[0],theme+"-mockup.png").toFile());
        }
    }
}
