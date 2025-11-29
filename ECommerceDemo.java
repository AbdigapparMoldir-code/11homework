import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
enum Role { CUSTOMER, ADMIN }
enum OrderStatus { NEW, PLACED, PAID, PROCESSING, IN_DELIVERY, COMPLETED, CANCELLED }
enum PaymentType { CARD, E_WALLET }
enum PaymentStatus { PENDING, SUCCESS, FAILED, REFUNDED }
enum ShipmentStatus { PENDING, IN_TRANSIT, DELIVERED, RETURNED }
abstract class User {
    protected UUID id = UUID.randomUUID();
    protected String name;
    protected String email;
    protected String address;
    protected String phone;
    protected Role role;
    public User(String name, String email, String address, String phone, Role role) {
        this.name = name;
        this.email = email;
        this.address = address;
        this.phone = phone;
        this.role = role;
    }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public void updateProfile(String name, String address, String phone) {
        this.name = name;
        this.address = address;
        this.phone = phone;
    }
    public void login() {
        System.out.println(name + " logged in (email: " + email + ")");
    }
}
class Customer extends User {
    private List<Order> orders = new ArrayList<>();
    private Cart cart = new Cart(this);
    private LoyaltyAccount loyalty = new LoyaltyAccount();
    public Customer(String name, String email, String address, String phone) {
        super(name, email, address, phone, Role.CUSTOMER);
    }
    public Cart getCart() { return cart; }
    public List<Order> getOrders() { return orders; }
    public LoyaltyAccount getLoyalty() { return loyalty; }

    public Order placeOrder(OrderService orderService) {
        Order order = orderService.createOrderFromCart(cart);
        if(order != null) {
            orders.add(order);
            cart.clear();
        }
        return order;
    }

    public void addOrder(Order o) { orders.add(o); }
}
class Administrator extends User {
    private List<AdminActionLog> logs = new ArrayList<>();
    public Administrator(String name, String email, String address, String phone) {
        super(name, email, address, phone, Role.ADMIN);
    }
    public void logAction(String action) {
        AdminActionLog l = new AdminActionLog(this, action);
        logs.add(l);
        System.out.println("ADMIN LOG: " + l);
    }
}
class Product {
    UUID id = UUID.randomUUID();
    String name;
    String description;
    BigDecimal price;
    String sku;
    Category category;
    boolean digital;
    public Product(String name, String description, BigDecimal price, String sku, Category category, boolean digital) {
        this.name = name; this.description = description; this.price = price; this.sku = sku; this.category = category; this.digital = digital;
    }
    @Override public String toString() { return name + " (" + sku + ") " + price; }
}
class Category {
    UUID id = UUID.randomUUID();
    String name;
    public Category(String name){ this.name = name; }
    @Override public String toString(){ return name; }
}

class Warehouse {
    UUID id = UUID.randomUUID();
    String name;
    String location;
    // inventory: SKU -> qty
    Map<String, Integer> inventory = new ConcurrentHashMap<>();

    public Warehouse(String name, String location) {
        this.name = name; this.location = location;
    }

    public void setStock(String sku, int qty) { inventory.put(sku, qty); }
    public int getStock(String sku) { return inventory.getOrDefault(sku, 0); }
    public boolean reserve(String sku, int qty) {
        synchronized (inventory) {
            int available = getStock(sku);
            if(available >= qty) { inventory.put(sku, available - qty); return true; }
            return false;
        }
    }
    public void release(String sku, int qty) {
        synchronized (inventory) {
            int available = getStock(sku);
            inventory.put(sku, available + qty);
        }
    }
    @Override public String toString(){ return name + "@" + location; }
}
class Cart {
    UUID id = UUID.randomUUID();
    Customer owner;
    Map<String, CartItem> items = new HashMap<>(); // key = sku
    PromoCode promo;

    public Cart(Customer owner) { this.owner = owner; }
    public void addItem(Product p, int qty) {
        CartItem it = items.getOrDefault(p.sku, new CartItem(p, 0));
        it.quantity += qty;
        items.put(p.sku, it);
        System.out.println("Cart: added " + qty + " x " + p.name);
    }
    public void removeItem(String sku) { items.remove(sku); }
    public BigDecimal calculateTotal() {
        BigDecimal total = BigDecimal.ZERO;
        for(CartItem ci : items.values()){
            total = total.add(ci.product.price.multiply(BigDecimal.valueOf(ci.quantity)));
        }
        if(promo != null && promo.isValid()) {
            total = total.subtract(promo.apply(total));
        }
        return total;
    }
    public void applyPromo(PromoCode p) {
        if(p != null && p.isValid()) { promo = p; System.out.println("Promo applied: " + p.code); }
        else System.out.println("Promo invalid");
    }
    public List<CartItem> getItems() { return new ArrayList<>(items.values()); }
    public void clear(){ items.clear(); promo = null; }
}
class CartItem {
    Product product;
    int quantity;
    public CartItem(Product p, int q){ this.product = p; this.quantity = q; }
}
class PromoCode {
    String code;
    double discountPercent;
    LocalDateTime validUntil;
    int usageLeft;
    public PromoCode(String code, double discountPercent, LocalDateTime validUntil, int usageLeft) {
        this.code = code; this.discountPercent = discountPercent; this.validUntil = validUntil; this.usageLeft = usageLeft;
    }
    public boolean isValid() {
        return LocalDateTime.now().isBefore(validUntil) && usageLeft > 0;
    }
    public BigDecimal apply(BigDecimal total) {
        if(!isValid()) return BigDecimal.ZERO;
        usageLeft--;
        return total.multiply(BigDecimal.valueOf(discountPercent / 100.0));
    }
}
class Order {
    UUID id = UUID.randomUUID();
    LocalDateTime createdAt = LocalDateTime.now();
    OrderStatus status = OrderStatus.NEW;
    Customer customer;
    List<OrderItem> items = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    Shipment shipment;
    Payment payment;
    public Order(Customer customer) { this.customer = customer; }
    public void addItem(OrderItem it) { items.add(it); total = total.add(it.unitPrice.multiply(BigDecimal.valueOf(it.quantity))); }
    public void place() { this.status = OrderStatus.PLACED; createdAt = LocalDateTime.now(); }
    public void cancel() { this.status = OrderStatus.CANCELLED; }
    public void pay(Payment p) { this.payment = p; if(p.status == PaymentStatus.SUCCESS) this.status = OrderStatus.PAID; }
    @Override public String toString() {
        return "Order{" + id + ", status=" + status + ", total=" + total + ", items=" + items.size() + "}";
    }
}
class OrderItem {
    Product product;
    int quantity;
    BigDecimal unitPrice;
    public OrderItem(Product p, int q) { this.product = p; this.quantity = q; this.unitPrice = p.price; }
    public BigDecimal subtotal() { return unitPrice.multiply(BigDecimal.valueOf(quantity)); }
    @Override public String toString(){ return product.name + " x" + quantity; }
}
class Payment {
    UUID id = UUID.randomUUID();
    PaymentType type;
    BigDecimal amount;
    PaymentStatus status = PaymentStatus.PENDING;
    LocalDateTime date;
    String transactionId;
    public Payment(PaymentType type, BigDecimal amount) { this.type = type; this.amount = amount; }
    @Override public String toString(){ return "Payment{" + id + "," + type + ","+ amount + "," + status + "}"; }
}
class Shipment {
    UUID id = UUID.randomUUID();
    String shippingAddress;
    ShipmentStatus status = ShipmentStatus.PENDING;
    String courierName;
    String trackingNumber;
    LocalDateTime estimatedDelivery;

    public Shipment(String shippingAddress) { this.shippingAddress = shippingAddress; }
    @Override public String toString(){ return "Shipment{" + trackingNumber + "," + status + "}"; }
}
class AdminActionLog {
    UUID id = UUID.randomUUID();
    Administrator admin;
    String action;
    LocalDateTime timestamp = LocalDateTime.now();
    public AdminActionLog(Administrator admin, String action){ this.admin = admin; this.action = action; }
    @Override public String toString(){ return "["+timestamp+"] "+admin.name+": "+action; }
}
class LoyaltyAccount {
    int points = 0;
    public void addPoints(int p){ points += p; }
    public boolean redeemPoints(int p) { if(points>=p){ points -= p; return true; } return false; }
    @Override public String toString(){ return "Loyalty{points=" + points + "}"; }
}
interface ProductFactory {
    Product create(Map<String,Object> params);
}
class PhysicalProductFactory implements ProductFactory {
    @Override
    public Product create(Map<String, Object> params) {
        return new Product(
                (String)params.get("name"),
                (String)params.getOrDefault("description",""),
                (BigDecimal)params.getOrDefault("price", BigDecimal.ZERO),
                (String)params.get("sku"),
                (Category)params.get("category"),
                false
        );
    }
}
class DigitalProductFactory implements ProductFactory {
    @Override
    public Product create(Map<String, Object> params) {
        return new Product(
                (String)params.get("name"),
                (String)params.getOrDefault("description","(digital)"),
                (BigDecimal)params.getOrDefault("price", BigDecimal.ZERO),
                (String)params.get("sku"),
                (Category)params.get("category"),
                true
        );
    }
}
interface PaymentGateway {
    PaymentResult charge(Payment p, Map<String,String> paymentDetails);
    RefundResult refund(String transactionId, BigDecimal amount);
}
class PaymentResult {
    boolean success; String transactionId; String message;
    PaymentResult(boolean s, String id, String m){ success=s; transactionId=id; message=m; }
}
class RefundResult { boolean success; String message; RefundResult(boolean s, String m){ success=s; message=m; } }

class MockPaymentGateway implements PaymentGateway {
    @Override
    public PaymentResult charge(Payment p, Map<String, String> paymentDetails) {
        String tx = "TX-" + UUID.randomUUID().toString().substring(0,8);
        System.out.println("MockPaymentGateway: charged " + p.amount + ", tx=" + tx);
        return new PaymentResult(true, tx, "OK");
    }
    @Override
    public RefundResult refund(String transactionId, BigDecimal amount) {
        System.out.println("MockPaymentGateway: refund " + amount + " for tx=" + transactionId);
        return new RefundResult(true, "Refunded");
    }
}
interface CourierIntegration {
    String createShipment(Shipment s);
    ShipmentStatus getStatus(String trackingNumber);
}
class MockCourierIntegration implements CourierIntegration {
    @Override public String createShipment(Shipment s) {
        String tn = "TRK-" + UUID.randomUUID().toString().substring(0,8);
        System.out.println("MockCourier: created shipment, tracking=" + tn);
        return tn;
    }
    @Override public ShipmentStatus getStatus(String trackingNumber) {
        return ShipmentStatus.IN_TRANSIT;
    }
}
class InventoryService {
    Map<String, Warehouse> warehouses = new HashMap<>(); // name -> warehouse
    public void addWarehouse(Warehouse w) { warehouses.put(w.name, w); }
    public boolean reserveProductAcrossWarehouses(String sku, int qty) {
        for(Warehouse w : warehouses.values()) {
            if(w.getStock(sku) >= qty) {
                boolean ok = w.reserve(sku, qty);
                if(ok) { System.out.println("Reserved " + qty + " of " + sku + " in " + w); return true; }
            }
        }
        System.out.println("Inventory: not enough stock for " + sku);
        return false;
    }
    public void releaseProduct(String sku, int qty) {
        for(Warehouse w : warehouses.values()) {
            w.release(sku, qty);
            System.out.println("Released " + qty + " of " + sku + " to " + w);
            return;
        }
    }
    public int getTotalStock(String sku) {
        int sum=0;
        for(Warehouse w: warehouses.values()) sum += w.getStock(sku);
        return sum;
    }
}
class PaymentService {
    PaymentGateway gateway;
    public PaymentService(PaymentGateway g){ this.gateway = g; }
    public PaymentResult processPayment(Payment p, Map<String,String> details) {
        PaymentResult res = gateway.charge(p, details);
        if(res.success) {
            p.status = PaymentStatus.SUCCESS; p.transactionId = res.transactionId; p.date = LocalDateTime.now();
        } else {
            p.status = PaymentStatus.FAILED;
        }
        return res;
    }
    public RefundResult refund(Payment p) {
        if(p.transactionId == null) return new RefundResult(false,"no tx");
        p.status = PaymentStatus.REFUNDED;
        return gateway.refund(p.transactionId, p.amount);
    }
}
class ShipmentService {
    CourierIntegration courier;
    public ShipmentService(CourierIntegration c) { this.courier = c; }
    public void createAndDispatch(Shipment s) {
        String tn = courier.createShipment(s);
        s.trackingNumber = tn;
        s.status = ShipmentStatus.IN_TRANSIT;
        s.estimatedDelivery = LocalDateTime.now().plusDays(3);
        System.out.println("Shipment dispatched: " + s);
    }
    public ShipmentStatus track(String trackingNumber) { return courier.getStatus(trackingNumber); }
}
class OrderService {
    InventoryService inventory;
    PaymentService paymentService;
    ShipmentService shipmentService;

    public OrderService(InventoryService inv, PaymentService pay, ShipmentService ship) {
        this.inventory = inv; this.paymentService = pay; this.shipmentService = ship;
    }

    public Order createOrderFromCart(Cart cart) {
        if(cart.getItems().isEmpty()) { System.out.println("Cart empty"); return null; }
        Order order = new Order(cart.owner);
        for(CartItem ci : cart.getItems()) {
            boolean ok = inventory.reserveProductAcrossWarehouses(ci.product.sku, ci.quantity);
            if(!ok) {
                for(OrderItem oi : order.items) inventory.releaseProduct(oi.product.sku, oi.quantity);
                System.out.println("Order creation failed due to stock");
                return null;
            }
            order.addItem(new OrderItem(ci.product, ci.quantity));
        }
        order.place();
        System.out.println("Order created: " + order);
        return order;
    }

    public boolean payOrder(Order order, PaymentType type, Map<String,String> paymentDetails) {
        Payment p = new Payment(type, order.total);
        PaymentResult res = paymentService.processPayment(p, paymentDetails);
        if(res.success) {
            order.pay(p);
            int points = order.total.intValue(); // simple: 1 point per currency unit
            order.customer.getLoyalty().addPoints(points);
            System.out.println("Payment success. Loyalty +" + points);
            return true;
        } else {
            for(OrderItem oi: order.items) inventory.releaseProduct(oi.product.sku, oi.quantity);
            System.out.println("Payment failed: " + res.message);
            return false;
        }
    }
    public void shipOrder(Order order) {
        if(order.status != OrderStatus.PAID) { System.out.println("Cannot ship: not paid"); return; }
        Shipment s = new Shipment(order.customer.address);
        shipmentService.createAndDispatch(s);
        order.shipment = s;
        order.status = OrderStatus.IN_DELIVERY;
    }
    public void completeOrder(Order order) {
        if(order.status == OrderStatus.IN_DELIVERY) {
            order.status = OrderStatus.COMPLETED;
            System.out.println("Order completed: " + order.id);
        }
    }
    public void cancelOrder(Order order) {
        if(order.status == OrderStatus.PLACED || order.status == OrderStatus.NEW) {
            order.cancel();
            for(OrderItem oi: order.items) inventory.releaseProduct(oi.product.sku, oi.quantity);
            System.out.println("Order cancelled and stock released.");
        } else {
            System.out.println("Cannot cancel order in status: " + order.status);
        }
    }
}
public class ECommerceDemo {
    public static void main(String[] args) {
        Category electronics = new Category("Electronics");
        ProductFactory physicalFactory = new PhysicalProductFactory();
        Map<String,Object> params = new HashMap<>();
        params.put("name","Smartphone X");
        params.put("description","Flagship smartphone");
        params.put("price", new BigDecimal("450.00"));
        params.put("sku","SMX-001");
        params.put("category", electronics);
        Product phone = physicalFactory.create(params);
        params.put("name","Ebook: Java Patterns");
        params.put("price", new BigDecimal("19.99"));
        params.put("sku","EB-001");
        Product ebook = new DigitalProductFactory().create(params);
        Warehouse w1 = new Warehouse("WH-A", "Almaty");
        w1.setStock("SMX-001", 10);
        w1.setStock("EB-001", 1000); // digital counted but not reserved
        Warehouse w2 = new Warehouse("WH-B", "Nur-Sultan");
        w2.setStock("SMX-001", 5);
        InventoryService inventoryService = new InventoryService();
        inventoryService.addWarehouse(w1);
        inventoryService.addWarehouse(w2);
        PaymentGateway pg = new MockPaymentGateway();
        CourierIntegration courier = new MockCourierIntegration();
        PaymentService paymentService = new PaymentService(pg);
        ShipmentService shipmentService = new ShipmentService(courier);
        OrderService orderService = new OrderService(inventoryService, paymentService, shipmentService);
        Customer alice = new Customer("Alice", "alice@example.com", "Almaty, 123", "+7701");
        Administrator admin = new Administrator("Admin", "admin@example.com", "HQ", "+7700");
        admin.logAction("created product SMX-001");
        alice.getCart().addItem(phone, 1);
        alice.getCart().addItem(ebook, 1);
        PromoCode promo = new PromoCode("WELCOME10", 10.0, LocalDateTime.now().plusDays(5), 100);
        alice.getCart().applyPromo(promo);
        System.out.println("Cart total: " + alice.getCart().calculateTotal());
        Order order = alice.placeOrder(orderService);
        if(order == null) { System.out.println("Order failed"); return; }
        Map<String,String> payDetails = new HashMap<>();
        payDetails.put("cardNumber","4242-..."); // mock
        boolean paid = orderService.payOrder(order, PaymentType.CARD, payDetails);
        if(paid) {
            orderService.shipOrder(order);
            System.out.println("Order status after ship: " + order.status);
        }
        System.out.println("Loyalty account: " + alice.getLoyalty());
        orderService.completeOrder(order);
        System.out.println("Final order status: " + order.status);
    }
}
