import java.io.*;
import java.util.*;

public class Main {
    record Request(int id, int days, int qty, double disc) {}

    public static void main(String[] args) {
        new File("output").mkdirs();
        try {
            Map<Integer, String> books = load("input/books.txt"), reqs = load("input/requests.txt");
            try (BufferedWriter rep = new BufferedWriter(new FileWriter("output/library_report.txt"));
                 BufferedWriter err = new BufferedWriter(new FileWriter("output/errors.txt", true))) {
                run(books, reqs, rep, err);
            }
        } catch (Exception e) {
            System.out.println("Run stopped: " + e.getMessage());
        }
    }

    static void run(Map<Integer, String> books, Map<Integer, String> reqs, BufferedWriter rep, BufferedWriter err) throws IOException {
        List<Book> cat = new ArrayList<>();
        int read = 0, ok = 0, bad = 0;

        for (var e : books.entrySet()) {
            read++;
            try {
                cat.add(parseBook(e.getValue()));
                ok++;
            } catch (Exception x) {
                bad++;
                log(err, e.getKey(), e.getValue(), x);
            }
        }

        rep.write(Book.libraryName + "\nCatalogue:\n");
        for (Book b : cat) b.display(rep);
        rep.write("\nRequests:\n");

        for (var e : reqs.entrySet()) {
            read++;
            try {
                Request r = parseRequest(e.getValue());
                Book b = Book.find(cat, r.id());
                b.calculateLateFine(r.days());
                b.totalCost(r.qty());
                b.totalCost(r.qty(), r.disc());

                String status = "issued";
                try {
                    issueOrThrow(b);
                    ok++;
                } catch (BookNotAvailableException | DepositNotPaidException x) {
                    status = "REFUSED - " + x.getMessage();
                    bad++;
                    log(err, e.getKey(), e.getValue(), x);
                }

                String refund = b instanceof PremiumBook p
                        ? String.format(Locale.US, "%.2f", p.refundDeposit(r.days())) : "n/a";
                rep.write(String.format(Locale.US,
                        "Line %d: bookId %d %s%n  issue: %s%n  late fine: %.2f%n  deposit refund: %s%n"
                        + "  purchase cost: %.2f%n  with %.1f%% discount: %.2f%n%n",
                        e.getKey(), r.id(), b.getTitle(), status, b.calculateLateFine(r.days()), refund,
                        b.totalCost(r.qty()), r.disc(), b.totalCost(r.qty(), r.disc())));
            } catch (Exception x) {
                bad++;
                log(err, e.getKey(), e.getValue(), x);
                rep.write("Line " + e.getKey() + ": REJECTED - " + x.getMessage() + "\n\n");
            }
        }

        rep.write("Costlier of first two books: bookId " + (cat.size() > 1 ? cat.get(0).comparePrice(cat.get(1)) : -1) + "\n");
        rep.write("Most valuable title: bookId " + Book.findPremiumTitle(cat) + "\n");
        rep.write("bookCount: " + Book.bookCount + "\n");
        String summary = "Records read: " + read + ", accepted: " + ok + ", rejected: " + bad;
        rep.write(summary + "\n");
        System.out.println(summary);
    }

    static void issueOrThrow(Book b) throws BookNotAvailableException, DepositNotPaidException {
        if (b.issueBook()) return;
        if (b instanceof PremiumBook p && !p.isDepositPaid()) throw new DepositNotPaidException("deposit not paid for " + b.getTitle());
        throw new BookNotAvailableException("no copies left for " + b.getTitle());
    }

    static Map<Integer, String> load(String path) throws Exception {
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) throw new FileNotFoundException(path + " not found");
        if (f.length() == 0) throw new EmptyInputFileException(path + " is empty");

        Map<Integer, String> lines = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s;
            for (int no = 1; (s = r.readLine()) != null; no++) {
                s = s.trim();
                if (!s.isEmpty() && !s.startsWith("#")) lines.put(no, s);
            }
        }

        var it = lines.entrySet().iterator();
        if (!it.hasNext() || Integer.parseInt(it.next().getValue()) <= 0) throw new EmptyInputFileException(path + ": N is missing or zero");
        it.remove();
        if (lines.isEmpty()) throw new EmptyInputFileException(path + " has no records");
        return lines;
    }

    static Book parseBook(String s) throws Exception {
        String[] f = split(s);
        try {
            if (f[0].equals("B") && f.length == 6) {
                return new Book(Integer.parseInt(f[1]), f[2], f[3], Double.parseDouble(f[4]), Integer.parseInt(f[5]));
            }
            if (f[0].equals("P") && f.length == 8) {
                PremiumBook p = new PremiumBook(Integer.parseInt(f[1]), f[2], f[3], Double.parseDouble(f[4]),
                        Integer.parseInt(f[5]), Double.parseDouble(f[6]));
                p.setDepositPaid(f[7].equalsIgnoreCase("true"));
                return p;
            }
        } catch (NumberFormatException e) {
            throw new InvalidRecordException("non-numeric value in: " + s);
        }
        throw new InvalidRecordException("bad record: " + s);
    }

    static Request parseRequest(String s) throws InvalidRecordException {
        String[] f = split(s);
        if (f.length != 4) throw new InvalidRecordException("bad request: " + s);
        try {
            return new Request(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), Double.parseDouble(f[3]));
        } catch (NumberFormatException e) {
            throw new InvalidRecordException("non-numeric value in: " + s);
        }
    }

    static String[] split(String s) {
        return Arrays.stream(s.split(",", -1)).map(String::trim).toArray(String[]::new);
    }

    static void log(BufferedWriter err, int line, String text, Exception e) throws IOException {
        err.write("line " + line + " | " + text + " | " + e.getClass().getSimpleName() + " | " + e.getMessage() + "\n");
    }
}

class Book {
    static String libraryName = "City Central Library";
    static double lateFee = 5;
    static int bookCount;

    private int id, copies;
    private String title, author;
    private double price;

    Book() { bookCount++; }

    Book(int id, String title, String author, double price, int copies) throws InvalidPriceException {
        this();
        if (price < 0) { bookCount--; throw new InvalidPriceException("price cannot be negative: " + price, price); }
        if (copies < 0) { bookCount--; throw new InvalidQuantityException("copies cannot be negative: " + copies); }
        this.id = id; this.title = title; this.author = author; this.price = price; this.copies = copies;
    }

    String getTitle() { return title; }

    boolean issueBook() {
        if (copies <= 0) return false;
        copies--;
        return true;
    }

    double calculateLateFine(int days) {
        if (days < 0) throw new InvalidQuantityException("days late cannot be negative: " + days);
        return days * lateFee;
    }

    double totalCost(int qty) {
        if (qty <= 0) throw new InvalidQuantityException("quantity must be greater than 0: " + qty);
        return price * qty;
    }

    double totalCost(int qty, double disc) {
        if (disc < 0 || disc > 100) throw new InvalidDiscountException("discount must be 0 to 100: " + disc);
        return totalCost(qty) * (1 - disc / 100);
    }

    int comparePrice(Book other) { return other.price > price ? other.id : id; }

    void display(BufferedWriter w) throws IOException {
        w.write(id + " | " + title + " | " + author + " | price " + price + " | copies " + copies + "\n");
    }

    static Book find(List<Book> books, int id) {
        return books.stream().filter(b -> b.id == id).findFirst().orElseThrow(() -> new BookNotFoundException("no book with id " + id));
    }

    static int findPremiumTitle(List<Book> books) {
        return books.stream().max(Comparator.comparingDouble(b -> b.price)).map(b -> b.id).orElse(-1);
    }
}

class PremiumBook extends Book {
    static int fineMultiplier = 2;

    private double deposit;
    private boolean paid;

    PremiumBook() {}

    PremiumBook(int id, String title, String author, double price, int copies, double deposit)
            throws InvalidPriceException, InvalidDepositException {
        super(id, title, author, price, copies);
        if (deposit < 0) { bookCount--; throw new InvalidDepositException("deposit cannot be negative: " + deposit); }
        this.deposit = deposit;
    }

    void setDepositPaid(boolean paid) { this.paid = paid; }

    boolean isDepositPaid() { return paid; }

    @Override
    boolean issueBook() { return paid && super.issueBook(); }

    @Override
    double calculateLateFine(int days) { return super.calculateLateFine(days) * fineMultiplier; }

    double refundDeposit(int days) { return Math.max(0, deposit - calculateLateFine(days)); }

    @Override
    void display(BufferedWriter w) throws IOException {
        super.display(w);
        w.write("   deposit " + deposit + " | paid " + paid + "\n");
    }
}

class InvalidPriceException extends Exception { final double value; InvalidPriceException(String m, double v) { super(m); value = v; } }
class InvalidDepositException extends Exception { InvalidDepositException(String m) { super(m); } }
class BookNotAvailableException extends Exception { BookNotAvailableException(String m) { super(m); } }
class DepositNotPaidException extends Exception { DepositNotPaidException(String m) { super(m); } }
class InvalidRecordException extends Exception { InvalidRecordException(String m) { super(m); } }
class EmptyInputFileException extends Exception { EmptyInputFileException(String m) { super(m); } }
class InvalidQuantityException extends RuntimeException { InvalidQuantityException(String m) { super(m); } }
class InvalidDiscountException extends RuntimeException { InvalidDiscountException(String m) { super(m); } }
class BookNotFoundException extends RuntimeException { BookNotFoundException(String m) { super(m); } }
