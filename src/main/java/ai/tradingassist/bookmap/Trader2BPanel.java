package ai.tradingassist.bookmap;

import java.awt.*;
import java.util.UUID;
import java.util.concurrent.*;
import javax.swing.*;
import velox.api.layer1.annotations.*;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.simplified.*;
import velox.gui.StrategyPanel;

@Layer1SimpleAttachable
@Layer1StrategyName("TradingAssist Trader2B Bridge — PAPER")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class Trader2BPanel implements CustomModule, CustomSettingsPanelProvider {
    private volatile boolean stopped = false;
    private String alias = "";
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "trader2b-http"); t.setDaemon(true); return t;
    });
    private StrategyPanel panel;
    private final JTextField url = new JTextField("http://127.0.0.1:8000", 24);
    private final JPasswordField token = new JPasswordField(24);
    private final JTextField symbol = new JTextField("", 12), account = new JTextField("", 12);
    private final JTextField orderId = new JTextField("", 36);
    private final JSpinner quantity = new JSpinner(new SpinnerNumberModel(1, 1, 10000, 1));
    private final JCheckBox armed = new JCheckBox("Enable external PAPER orders");
    private final JTextArea output = new JTextArea(12, 50);
    private final JButton buy = new JButton("Buy"), sell = new JButton("Sell"),
        cancel = new JButton("Cancel order"), status = new JButton("Lookup order"), health = new JButton("Check API");
    private boolean busy;
    // An uncertain mutation locks new orders until an explicit backend/account reconciliation.
    private boolean uncertain;
    private final JButton reconcile = new JButton("I verified the order/account");

    @Override public void initialize(String alias, InstrumentInfo info, Api api, InitialState state) {
        this.alias = alias; // Never silently translate a Bookmap alias to a broker symbol.
    }
    @Override public void stop() {
        stopped = true;
        worker.shutdownNow();
        SwingUtilities.invokeLater(() -> { armed.setSelected(false); token.setText(""); update(); });
    }
    @Override public StrategyPanel[] getCustomSettingsPanels() {
        if (panel != null) return new StrategyPanel[]{panel};
        panel = new StrategyPanel("External Trader2B — PAPER only");
        panel.setLayout(new BorderLayout());
        JPanel form = new JPanel(new GridLayout(0, 2, 5, 5));
        form.add(new JLabel("Bookmap chart (reference only)")); form.add(new JLabel(alias));
        form.add(new JLabel("FastAPI base URL")); form.add(url);
        form.add(new JLabel("Bridge bearer token (not saved)")); form.add(token);
        form.add(new JLabel("Broker symbol (enter explicitly)")); form.add(symbol);
        form.add(new JLabel("Paper account ID")); form.add(account);
        form.add(new JLabel("Shares")); form.add(quantity);
        form.add(new JLabel("Client order ID / lookup ID")); form.add(orderId);
        form.add(armed); form.add(health);
        JPanel buttons = new JPanel();
        for (JButton b : new JButton[]{buy,sell,cancel,status,reconcile}) buttons.add(b);
        JPanel top = new JPanel(new BorderLayout()); top.add(form, BorderLayout.CENTER); top.add(buttons, BorderLayout.SOUTH);
        panel.add(top, BorderLayout.NORTH);
        output.setEditable(false); output.setLineWrap(true); output.setWrapStyleWord(true);
        output.setText("Paper-only external bridge. Backend must enforce paper accounts.\nBookmap SIM does not control external HTTP calls.\nNo synchronization with Bookmap native positions or P&L.\nSell may open a short; cancel does not flatten a position.");
        panel.add(new JScrollPane(output), BorderLayout.CENTER);
        armed.addActionListener(e -> update());
        buy.addActionListener(e -> submit("buy")); sell.addActionListener(e -> submit("sell"));
        health.addActionListener(e -> run("GET", "/health", null, null));
        status.addActionListener(e -> {
            try { run("GET", "/v1/orders/by-client-id/" + BridgeClient.identifier(orderId.getText().trim())
                + "?account_id=" + BridgeClient.identifier(account.getText().trim()) + "&environment=paper", null, null); }
            catch (Exception ex) { show(ex.getMessage()); }
        });
        cancel.addActionListener(e -> {
            try {
                String id = BridgeClient.identifier(orderId.getText().trim());
                String acct = BridgeClient.identifier(account.getText().trim());
                if (!confirm("Cancel PAPER order " + id + " in account " + acct + "?")) return;
                run("POST", "/v1/orders/by-client-id/"+id+"/cancel",
                    "{\"account_id\":\""+acct+"\",\"environment\":\"paper\"}", UUID.randomUUID().toString());
            } catch (Exception ex) { show(ex.getMessage()); }
        });
        reconcile.addActionListener(e -> {
            if (confirm("Have you verified the order and position directly with the backend/broker?\nDo not resubmit an order whose outcome is unknown.")) {
                uncertain = false; update();
            }
        });
        update(); return new StrategyPanel[]{panel};
    }
    private void submit(String side) {
        try {
            quantity.commitEdit();
            String id = UUID.randomUUID().toString();
            String sym = BridgeClient.identifier(symbol.getText().trim());
            String acct = BridgeClient.identifier(account.getText().trim());
            int qty = (Integer)quantity.getValue();
            String body = BridgeClient.order(id, sym, acct, side, qty);
            if (!confirm("PAPER " + side.toUpperCase() + " " + qty + " " + sym + " MARKET / DAY\nAccount: " + acct + "\nEndpoint: " + url.getText() + "\nSell can open a short. Continue?")) return;
            orderId.setText(id);
            run("POST", "/v1/orders", body, id);
        } catch (Exception ex) { show(ex.getMessage()); }
    }
    private boolean confirm(String message) {
        return JOptionPane.showConfirmDialog(panel, message, "External paper order", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }
    private void run(String method, String path, String body, String key) {
        if (stopped || busy || (body != null && !armed.isSelected())) return;
        final BridgeClient client;
        try { client = new BridgeClient(url.getText().trim(), new String(token.getPassword())); }
        catch (Exception ex) { show(ex.getMessage()); return; }
        busy = true; update(); show("Sending " + method + " " + path + (key == null ? "" : "\nRequest ID: " + key));
        worker.submit(() -> {
            if (stopped) return;
            String result; boolean unknown = false;
            try { result = client.request(method, path, body, key); }
            catch (Exception ex) {
                unknown = body != null;
                result = (unknown ? "OUTCOME UNKNOWN — do not resend. Look up the client order ID and reconcile with broker.\n" : "Request failed.\n") + ex.getClass().getSimpleName();
            }
            // Treat ALL mutation replies as requiring inspection, including rejection/5xx.
            // Avoid parsing an invented broker status or declaring a fill from an HTTP code.
            final boolean lock = unknown || body != null;
            final String message = result;
            SwingUtilities.invokeLater(() -> {
                if (stopped) return;
                busy = false; uncertain |= lock; show(message); update();
            });
        });
    }
    private void show(String s) { output.append("\n\n" + s); output.setCaretPosition(output.getDocument().getLength()); }
    private void update() {
        boolean ready = !stopped && !busy;
        buy.setEnabled(ready && armed.isSelected() && !uncertain);
        sell.setEnabled(ready && armed.isSelected() && !uncertain);
        cancel.setEnabled(ready && armed.isSelected()); status.setEnabled(ready); health.setEnabled(ready);
        reconcile.setEnabled(ready && uncertain);
        for (JComponent c : new JComponent[]{url,token,symbol,account,quantity,orderId,armed}) c.setEnabled(ready);
    }
}
