package io.github.victorjspinto.chatbottest;

import static com.github.messenger4j.MessengerPlatform.CHALLENGE_REQUEST_PARAM_NAME;
import static com.github.messenger4j.MessengerPlatform.MODE_REQUEST_PARAM_NAME;
import static com.github.messenger4j.MessengerPlatform.SIGNATURE_HEADER_NAME;
import static com.github.messenger4j.MessengerPlatform.VERIFY_TOKEN_REQUEST_PARAM_NAME;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.messenger4j.MessengerPlatform;
import com.github.messenger4j.exceptions.MessengerApiException;
import com.github.messenger4j.exceptions.MessengerIOException;
import com.github.messenger4j.exceptions.MessengerVerificationException;
import com.github.messenger4j.receive.MessengerReceiveClient;
import com.github.messenger4j.receive.handlers.FallbackEventHandler;
import com.github.messenger4j.receive.handlers.PostbackEventHandler;
import com.github.messenger4j.receive.handlers.QuickReplyMessageEventHandler;
import com.github.messenger4j.receive.handlers.TextMessageEventHandler;
import com.github.messenger4j.send.MessengerSendClient;
import com.github.messenger4j.send.QuickReply;
import com.github.messenger4j.send.buttons.Button;
import com.github.messenger4j.send.templates.GenericTemplate;
import com.github.messenger4j.send.templates.ReceiptTemplate;

@RestController
@RequestMapping("/callback")
public class WebhookController {

	private static final Logger LOGGER = LoggerFactory.getLogger(WebhookController.class);

	private final MessengerReceiveClient receiveClient;
	private final MessengerSendClient sendClient;

	public WebhookController(@Value("${messenger4j.appSecret}") final String appSecret,
			@Value("${messenger4j.verifyToken}") final String verifyToken,
			final MessengerSendClient sendClient) {

		this.receiveClient = MessengerPlatform.newReceiveClientBuilder(appSecret, verifyToken)
				.onTextMessageEvent(textMessageEventHandler())
				.onQuickReplyMessageEvent(quickReplyMessageEventHandler())
				.onPostbackEvent(postbackEventHandler())
				.fallbackEventHandler(fallbackEventHandler())
				.build();

		this.sendClient = sendClient;
	}

	@GetMapping
	public ResponseEntity<String> checkToken(
			@RequestParam(value = CHALLENGE_REQUEST_PARAM_NAME, required = true) String challenge,
			@RequestParam(value = MODE_REQUEST_PARAM_NAME, required = true) String mode,
			@RequestParam(value = VERIFY_TOKEN_REQUEST_PARAM_NAME, required = true) String token) {

		if (!token.equals("batatinha")) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Wrong verification token");
		}

		return ResponseEntity.ok(challenge);
	}

	@PostMapping
	public ResponseEntity<Void> receiveMessage(@RequestBody final String payload,
			@RequestHeader(SIGNATURE_HEADER_NAME) final String signature) {

		LOGGER.debug("Received Messenger Platform callback - payload: {} | signature: {}", payload, signature);

		try {
            this.receiveClient.processCallbackPayload(payload, signature);
            LOGGER.info("Processed callback payload successfully");
            return ResponseEntity.ok().build();
        } catch (MessengerVerificationException e) {
        	LOGGER.warn("Processing of callback payload failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }	
	}
	

	private FallbackEventHandler fallbackEventHandler() {
		return event -> {
            LOGGER.debug("Received FallbackEvent: {}", event);

            final String senderId = event.getSender().getId();
            LOGGER.info("Received unsupported message from user '{}'", senderId);
        };
	}
	
	private TextMessageEventHandler textMessageEventHandler() {
		return (event) -> {
			LOGGER.info("Received message '{}' with text '{}' from user '{}' at '{}'", event.getMid(), event.getText(),
					event.getSender().getId(), event.getTimestamp());
			try {
				if(event.getText().equals("Step1")) {
					sendStepOneResponse(event.getSender().getId());
				} else if (event.getText().toLowerCase().equals("recibo")) {
					sendReceiptTemplate(event.getSender().getId());
				} else {
					sendClient.sendTextMessage(event.getSender().getId(), "Olá!");
				}
			} catch (MessengerApiException | MessengerIOException e) {
				handleSendException(e);
			}
		};
	}
	

	private void sendReceiptTemplate(String recipientId) {
		final String uniqueReceiptId = "order-" + Math.floor(Math.random() * 1000);

        final ReceiptTemplate receiptTemplate = ReceiptTemplate.newBuilder("Peter Chang", uniqueReceiptId, "USD", "Visa 1234")
                .timestamp(1428444852L)
                .addElements()
                    .addElement("Oculus Rift", 599.00f)
                        .subtitle("Includes: headset, sensor, remote")
                        .quantity(1)
                        .currency("USD")
                        .imageUrl("http://img.olx.com.br/images/79/798701037760443.jpg")
                        .toList()
                    .addElement("Samsung Gear VR", 99.99f)
                        .subtitle("Frost White")
                        .quantity(1)
                        .currency("USD")
                        .imageUrl("http://img.olx.com.br/images/79/798701037760443.jpg")
                        .toList()
                    .done()
                .addAddress("1 Hacker Way", "Menlo Park", "94025", "CA", "US").done()
                .addSummary(626.66f)
                    .subtotal(698.99f)
                    .shippingCost(20.00f)
                    .totalTax(57.67f)
                    .done()
                .addAdjustments()
                    .addAdjustment().name("New Customer Discount").amount(-50f).toList()
                    .addAdjustment().name("$100 Off Coupon").amount(-100f).toList()
                    .done()
                .build();

        try {
        	this.sendClient.sendTemplate(recipientId, receiptTemplate);
        } catch(Exception e) {
        	handleSendException(e);
        }
	}

	private PostbackEventHandler postbackEventHandler() {
		return (event) -> {
			String payload = event.getPayload();
			LOGGER.info("Received postback for user '{}' and page '{}' with payload '{}' at '{}'",
					event.getSender().getId(), event.getRecipient().getId(),
					payload, event.getTimestamp());
			
			if(payload.equals("SEARCH_PRODUCTS")) {
				sendStepTwoResponse(event.getSender().getId());
			} else if (payload.startsWith("REGION")) {
				sendStepThreeResponse(event.getSender().getId());
			} else if (payload.startsWith("STATE")) {
				sendStepFourResponse(event.getSender().getId());
			} else if (payload.startsWith("CATEGORY")) {
				sendStepFiveResponse(event.getSender().getId());
			}
		};
	}
	
	private QuickReplyMessageEventHandler quickReplyMessageEventHandler() {
		return (event) -> {
			String payload = event.getQuickReply().getPayload();
			LOGGER.info("Received quick reply for user '{}' and page '{}' with payload '{}' at '{}'",
					event.getSender().getId(), event.getRecipient().getId(),
					payload, event.getTimestamp());
			
			if(payload.equals("SEARCH_PRODUCTS")) {
				sendStepTwoResponse(event.getSender().getId());
			} else if (payload.startsWith("REGION")) {
				sendStepThreeResponse(event.getSender().getId());
			} else if (payload.startsWith("STATE")) {
				sendStepFourResponse(event.getSender().getId());
			} else if (payload.startsWith("CATEGORY")) {
				sendStepFiveResponse(event.getSender().getId());
			}
		};
	}

	private void sendStepFiveResponse(String senderId) {
		List<Button> buttons = Button.newListBuilder()
		        .addUrlButton("Ver detalhes", "http://lmgtfy.com/?q=Product").toList()
		        .addPostbackButton("Ver outras ofertas", "CATEGORY_1").toList()
		        .addPostbackButton("Fazer nova busca", "SEARCH_PRODUCT").toList()
		        .build();
		
		 GenericTemplate receipt = GenericTemplate.newBuilder()
	        .addElements()
	            .addElement("Xbox 360 preto 450 novo")
	                .itemUrl("http://am.olx.com.br/regiao-de-manaus/videogames/vendo-xbox-360-550-r-371694662?xtmc=xbox+360&xtnp=1&xtcr=1")
	                .imageUrl("http://img.olx.com.br/images/79/798701037760443.jpg")
	                .subtitle("Video Game novinho\nR$450\nRegião dos Lagos")
	                .buttons(buttons)
	                .toList()
                .addElement("Xbox 360 preto 450 novo")
	                .itemUrl("http://am.olx.com.br/regiao-de-manaus/videogames/vendo-xbox-360-550-r-371694662?xtmc=xbox+360&xtnp=1&xtcr=1")
	                .imageUrl("http://img.olx.com.br/images/79/798701037760443.jpg")
	                .subtitle("Video Game novinho\nR$450\nRegião dos Lagos")
	                .buttons(buttons)
	                .toList()
                .addElement("Xbox 360 preto 450 novo")
	                .itemUrl("http://am.olx.com.br/regiao-de-manaus/videogames/vendo-xbox-360-550-r-371694662?xtmc=xbox+360&xtnp=1&xtcr=1")
	                .imageUrl("http://img.olx.com.br/images/79/798701037760443.jpg")
	                .subtitle("Video Game novinho\nR$450\nRegião dos Lagos")
	                .buttons(buttons)
	                .toList()
	        .done()
	        .build();
		
		try {
			sendClient.sendTemplate(senderId, receipt);
		} catch (MessengerApiException | MessengerIOException e) {
			handleSendException(e);
		}

	}

	private void sendStepFourResponse(String senderId) {
    	List<QuickReply> quickreplies = QuickReply.newListBuilder()
	        	.addTextQuickReply("Animais e acessórios", "CATEGORY_1")
	        .toList()
	        	.addTextQuickReply("Bebês e crianças", "CATEGORY_2")
	        .toList()
	        	.addTextQuickReply("Músicas e hobbies", "CATEGORY_3")
	        .toList()
	        	.addTextQuickReply("Moda e beleza", "CATEGORY_4")
	        .toList()
	        	.addTextQuickReply("Para sua casa", "CATEGORY_5")
	        .toList()
	        	.addTextQuickReply("Esportes", "CATEGORY_6")
	        .toList()
	        	.addTextQuickReply("Imóveis", "CATEGORY_7")
	        .toList()
	        	.addTextQuickReply("Empregos e negócios", "CATEGORY_8")
	        .toList()
	        	.addTextQuickReply("Veículos e barcos", "CATEGORY_9")
	        .toList()
	        .build();
    	try {
			String message = "Em qual categoria você quer encontrar produtos?";
			sendClient.sendTextMessage(senderId, message, quickreplies);
		} catch (MessengerApiException | MessengerIOException e) {
			handleSendException(e);
		}
	}


	private void sendStepThreeResponse(String senderId) {
    	List<QuickReply> quickreplies = QuickReply.newListBuilder()
	        	.addTextQuickReply("Rio de Janeiro", "STATE_RJ")
	        .toList()
	        	.addTextQuickReply("São Paulo", "STATE_SP")
	        .toList()
	        	.addTextQuickReply("Minas Gerais", "STATE_MG")
	        .toList()
	        	.addTextQuickReply("Espirito Santo", "STATE_ES")
	        .toList()
	        .build();
    	try {
			String message = "Agora escolha o estado onde você vive!";
			sendClient.sendTextMessage(senderId, message, quickreplies);
		} catch (MessengerApiException | MessengerIOException e) {
			handleSendException(e);
		}
	}

	private void sendStepTwoResponse(String senderId) {
    	List<QuickReply> quickreplies = QuickReply.newListBuilder()
    			.addLocationQuickReply()
    		.toList()
	        	.addTextQuickReply("Sudeste", "REGION_SOUTEAST")
	        .toList()
	        	.addTextQuickReply("Sul", "REGION_SOUTH")
	        .toList()
	        	.addTextQuickReply("Norte", "REGION_NORTH")
	        .toList()
	        	.addTextQuickReply("Nordeste", "REGION_NORTHEAST")
	        .toList()
	        	.addTextQuickReply("Centro Oeste", "REGION_CENTERWEST")
	        .toList()
	        .build();
    	try {
			String message = "Ótimo! Escolha a região onde você vive!";
			sendClient.sendTextMessage(senderId, message, quickreplies);
		} catch (MessengerApiException | MessengerIOException e) {
			handleSendException(e);
		}
	}

	private void sendStepOneResponse(String senderId) {
    	List<QuickReply> quickreplies = QuickReply.newListBuilder()
	        	.addTextQuickReply("Procurar produtos", "SEARCH_PRODUCTS")
	        .toList()
	        	.addTextQuickReply("Desapegar", "SEARCH_PRODUCTS")
	        .toList()
	        	.addTextQuickReply("Tirar dúvidas", "SEARCH_PRODUCTS")
	        .toList()
	        .build();
    	try {
			String message = "Olá {nome}! Sou seu assistente da OLX. Posso responder dúvidas, ajudar a achar produtos ou desapegar de alguma coisa! O que você deseja fazer?";
			sendClient.sendTextMessage(senderId, message, quickreplies);
		} catch (MessengerApiException | MessengerIOException e) {
			handleSendException(e);
		}
	}

	private void handleSendException(Exception e) {
    	LOGGER.error("Message could not be sent. An unexpected error occurred.", e);
    }

}
