/*
 * Copyright 2017 Alfa Laboratory
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.alfabank.alfatest.cucumber.api;

import com.codeborne.selenide.Condition;
import com.codeborne.selenide.ElementsCollection;
import com.codeborne.selenide.ElementsContainer;
import com.codeborne.selenide.SelenideElement;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.support.FindBy;
import ru.alfabank.alfatest.cucumber.annotations.Hidden;
import ru.alfabank.alfatest.cucumber.annotations.Name;
import ru.alfabank.alfatest.cucumber.annotations.Optional;
import ru.alfabank.alfatest.cucumber.utils.Reflection;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.codeborne.selenide.Condition.*;
import static com.codeborne.selenide.Selenide.$$;
import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static ru.alfabank.tests.core.helpers.PropertyLoader.loadProperty;

/*
 * Класс для реализации паттерна PageObject
 */
@Slf4j
public abstract class AkitaPage extends ElementsContainer {
    /**
     * Стандартный таймаут ожидания элементов в миллисекундах
     */
    private static final String WAITING_APPEAR_TIMEOUT_IN_MILLISECONDS = "8000";
    private static final Integer TIMEOUT = Integer.parseInt(loadProperty("waitingAppearTimeout",
            WAITING_APPEAR_TIMEOUT_IN_MILLISECONDS));

    /**
     * Список всех элементов страницы
     */
    private Map<String, Object> namedElements;
    /**
     * Список элементов страницы, не помеченных аннотацией "Optional" или "Hidden"
     */
    private List<SelenideElement> primaryElements;

    /**
     * Список элементов страницы, помеченных аннотацией "Hidden"
     */
    private List<SelenideElement> hiddenElements;

    /**
     * Список списочных элементов страницы, не помеченных аннотацией "Optional" или "Hidden"
     */
    private List<ElementsCollection> primaryElementCollections;

    public AkitaPage() {
        super();
    }

    /**
     * Получение блока со страницы по имени (аннотированного "Name")
     */
    public AkitaPage getBlock(String blockName) {
        return java.util.Optional.ofNullable(AkitaScenario.getInstance().getPage(blockName).initialize())
                .orElseThrow(() -> new IllegalArgumentException("Блок " + blockName + " не описан на странице " + this.getClass().getName()));
    }

    /**
     * Получение списка блоков со страницы по имени (аннотированного "Name")
     */
    @SuppressWarnings("unchecked")
    public List<AkitaPage> getBlocksList(String listName) {
        Object value = namedElements.get(listName);
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("Список " + listName + " не описан на странице " + this.getClass().getName());
        }
        Stream<Object> s = ((List<Object>) value).stream();
        return s.map(AkitaPage::castToAkitaPage).collect(toList());
    }

    /**
     * Получение списка из элементов блока со страницы по имени (аннотированного "Name")
     */
    public List<SelenideElement> getBlockElements(String blockName) {
        return getBlock(blockName).namedElements.values().stream()
                .map(o -> ((SelenideElement) o)).collect(toList());
    }

    /**
     * Получение элемента блока со страницы по имени (аннотированного "Name")
     */
    public SelenideElement getBlockElement(String blockName, String elementName) {
        return ((SelenideElement) getBlock(blockName).namedElements.get(elementName));
    }

    /**
     * Получение элемента со страницы по имени (аннотированного "Name")
     */
    public SelenideElement getElement(String elementName) {
        return (SelenideElement) java.util.Optional.ofNullable(namedElements.get(elementName))
                .orElseThrow(() -> new IllegalArgumentException("Элемент " + elementName + " не описан на странице " + this.getClass().getName()));
    }

    /**
     * Получение элемента-списка со страницы по имени
     */
    public ElementsCollection getElementsList(String listName) {
        Object value = namedElements.get(listName);
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("Список " + listName + " не описан на странице " + this.getClass().getName());
        }
        FindBy listSelector = Arrays.stream(this.getClass().getDeclaredFields())
                .filter(f -> f.getDeclaredAnnotation(Name.class) != null && f.getDeclaredAnnotation(Name.class).value().equals(listName))
                .map(f -> f.getDeclaredAnnotation(FindBy.class))
                .findFirst().orElse(null);
        FindBy.FindByBuilder findByBuilder = new FindBy.FindByBuilder();
        return $$(findByBuilder.buildIt(listSelector, null));
    }

    /**
     * Получение текстов всех элементов, содержащихся в элементе-списке,
     * состоящего как из редактируемых полей, так и статичных элементов по имени
     * Используется метод innerText(), который получает как видимый, так и скрытый текст из элемента,
     * обрезая перенос строк и пробелы в конце и начале строчки.
     */
    public List<String> getAnyElementsListInnerTexts(String listName) {
        List<SelenideElement> elementsList = getElementsList(listName);
        return elementsList.stream()
                .map(element -> element.getTagName().equals("input")
                        ? Objects.requireNonNull(element.getValue()).trim()
                        : element.innerText().trim()
                )
                .collect(toList());
    }

    /**
     * Получение текста элемента, как редактируемого поля, так и статичного элемента по имени
     */
    public String getAnyElementText(String elementName) {
        return getAnyElementText(getElement(elementName));
    }

    /**
     * Получение текста элемента, как редактируемого поля, так и статичного элемента по значению элемента
     */
    public String getAnyElementText(SelenideElement element) {
        if (element.getTagName().equals("input") || element.getTagName().equals("textarea")) {
            return element.getValue();
        } else {
            return element.getText();
        }
    }

    /**
     * Получение текстов всех элементов, содержащихся в элементе-списке,
     * состоящего как из редактируемых полей, так и статичных элементов по имени
     */
    public List<String> getAnyElementsListTexts(String listName) {
        List<SelenideElement> elementsList = getElementsList(listName);
        return elementsList.stream()
                .map(element -> element.getTagName().equals("input")
                        ? element.getValue()
                        : element.getText()
                )
                .collect(toList());
    }

    /**
     * Получение всех элементов страницы, не помеченных аннотацией "Optional" или "Hidden"
     */
    public List<SelenideElement> getPrimaryElements() {
        if (primaryElements == null) {
            primaryElements = readWithWrappedElements();
        }
        return new ArrayList<>(primaryElements);
    }

    /**
     * Получение всех элементов страницы, помеченных аннотацией "Hidden"
     */
    public List<SelenideElement> getHiddenElements() {
        if (hiddenElements == null) {
            hiddenElements = readWithHiddenElements();
        }
        return new ArrayList<>(hiddenElements);
    }

    /**
     * Получения всех элементов с типом ElementsCollection, не помеченных аннотацией "Optional" или "Hidden"
     * @return
     */
    public List<ElementsCollection> getPrimaryElementsCollections() {
        if (primaryElementCollections == null) {
            primaryElementCollections = readPrimaryElementsCollections();
        }

        return new ArrayList<>(primaryElementCollections);
    }

    /**
     * Обертка над AkitaPage.isAppeared
     * Ex: AkitaPage.appeared().doSomething();
     */
    public final AkitaPage appeared() {
        isAppeared();
        return this;
    }

    /**
     * Обертка над AkitaPage.isDisappeared
     * Ex: AkitaPage.disappeared().doSomething();
     */
    public final AkitaPage disappeared() {
        isDisappeared();
        return this;
    }

    /**
     * Проверка того, что элементы, не помеченные аннотацией "Optional", отображаются,
     * а элементы, помеченные аннотацией "Hidden", скрыты.
     */
    protected void isAppeared() {

        getPrimaryElements().forEach(elem ->
                elem.shouldBe(appear, Duration.ofMillis(TIMEOUT)));
        getHiddenElements().forEach(elem ->
                elem.shouldBe(hidden, Duration.ofMillis(TIMEOUT)));

        eachForm(AkitaPage::isAppeared);
    }

    @SuppressWarnings("unchecked")
    private void eachForm(Consumer<AkitaPage> func) {
        Arrays.stream(getClass().getDeclaredFields())
                .filter(f -> f.getDeclaredAnnotation(Optional.class) == null && f.getDeclaredAnnotation(Hidden.class) == null)
                .forEach(f -> {
                    if (AkitaPage.class.isAssignableFrom(f.getType())) {
                        AkitaPage akitaPage = AkitaScenario.getInstance().getPage((Class<? extends AkitaPage>) f.getType()).initialize();
                        func.accept(akitaPage);
                    }
                });
    }

    /**
     * Проверка, что все элементы страницы, не помеченные аннотацией "Optional" или "Hidden", исчезли
     */
    protected void isDisappeared() {
        getPrimaryElements().forEach(elem ->
                elem.shouldNotBe(exist, Duration.ofMillis(TIMEOUT)));
    }

    /**
     * Обертка над AkitaPage.isAppearedInIe
     * Ex: AkitaPage.ieAppeared().doSomething();
     * Используется при работе с IE
     */
    public final AkitaPage ieAppeared() {
        isAppearedInIe();
        return this;
    }

    /**
     * Обертка над AkitaPage.isDisappearedInIe
     * Ex: AkitaPage.ieDisappeared().doSomething();
     * Используется при работе с IE
     */
    public final AkitaPage ieDisappeared() {
        isDisappearedInIe();
        return this;
    }

    /**
     * Проверка того, что элементы, не помеченные аннотацией "Optional", отображаются,
     * а элементы, помеченные аннотацией "Hidden", скрыты.
     */
    protected void isAppearedInIe() {

        getPrimaryElements().forEach(elem ->
                elem.shouldBe(appear, Duration.ofMillis(TIMEOUT)));
        getHiddenElements().forEach(elem ->
                elem.shouldBe(hidden, Duration.ofMillis(TIMEOUT)));

        eachForm(AkitaPage::isAppearedInIe);
    }

    /**
     * Проверка, что все элементы страницы, не помеченные аннотацией "Optional" или "Hidden", исчезли
     */
    protected void isDisappearedInIe() {
        getPrimaryElements().forEach(elem ->
                elem.shouldNotHave(exist, Duration.ofMillis(TIMEOUT)));
    }


    /**
     * Обертка над Selenide.waitUntil для произвольного количества элементов
     *
     * @param condition Selenide.Condition
     * @param timeout   максимальное время ожидания для перехода элементов в заданное состояние
     * @param elements  произвольное количество selenide-элементов
     */
    public void waitElementsUntil(Condition condition, int timeout, SelenideElement... elements) {
        Spectators.waitElementsUntil(condition, timeout, elements);
    }

    /**
     * Обертка над Selenide.waitUntil для работы со списком элементов
     *
     * @param elements список selenide-элементов
     */
    public void waitElementsUntil(Condition condition, int timeout, ElementsCollection elements) {
        Spectators.waitElementsUntil(condition, timeout, elements);
    }

    /**
     * Проверка, что все переданные элементы в течении заданного периода времени
     * перешли в состояние Selenide.Condition
     *
     * @param elementNames произвольное количество строковых переменных с именами элементов
     */
    public void waitElementsUntil(Condition condition, int timeout, String... elementNames) {
        List<SelenideElement> elements = Arrays.stream(elementNames)
                .map(name -> namedElements.get(name))
                .flatMap(v -> v instanceof List ? ((List<?>) v).stream() : Stream.of(v))
                .map(AkitaPage::castToSelenideElement)
                .filter(Objects::nonNull)
                .collect(toList());
        Spectators.waitElementsUntil(condition, timeout, elements);
    }

    /**
     * Поиск элемента по имени внутри списка элементов
     */
    public static SelenideElement getButtonFromListByName(List<SelenideElement> listButtons, String nameOfButton) {
        List<String> names = new ArrayList<>();
        for (SelenideElement button : listButtons) {
            names.add(button.getText());
        }
        return listButtons.get(names.indexOf(nameOfButton));
    }

    /**
     * Приведение объекта к типу SelenideElement
     */
    private static SelenideElement castToSelenideElement(Object object) {
        if (object instanceof SelenideElement) {
            return (SelenideElement) object;
        }
        return null;
    }

    private static AkitaPage castToAkitaPage(Object object) {
        if (object instanceof AkitaPage) {
            return (AkitaPage) object;
        }
        return null;
    }


    public AkitaPage initialize() {
        namedElements = readNamedElements();
        primaryElements = readWithWrappedElements();
        hiddenElements = readWithHiddenElements();
        return this;
    }

    /**
     * Поиск и инициализации элементов страницы
     */
    private Map<String, Object> readNamedElements() {
        checkNamedAnnotations();
        return Arrays.stream(getClass().getDeclaredFields())
                .filter(f -> f.getDeclaredAnnotation(Name.class) != null)
                .peek(this::checkFieldType)
                .collect(toMap(f -> f.getDeclaredAnnotation(Name.class).value(), this::extractFieldValueViaReflection));
    }

    private void checkFieldType(Field f) {
        if (!SelenideElement.class.isAssignableFrom(f.getType())
                && !AkitaPage.class.isAssignableFrom(f.getType())) {
            checkCollectionFieldType(f);
        }
    }

    private void checkCollectionFieldType(Field f) {
        if (ElementsCollection.class.isAssignableFrom(f.getType())) {
            return;
        } else if (List.class.isAssignableFrom(f.getType())) {
            ParameterizedType listType = (ParameterizedType) f.getGenericType();
            Class<?> listClass = (Class<?>) listType.getActualTypeArguments()[0];
            if (SelenideElement.class.isAssignableFrom(listClass) || AkitaPage.class.isAssignableFrom(listClass)) {
                return;
            }
        }
        throw new IllegalStateException(
                format("Поле с аннотацией @Name должно иметь тип SelenideElement или List<SelenideElement>.\n" +
                        "Если поле описывает блок, оно должно принадлежать классу, унаследованному от AkitaPage.\n" +
                        "Найдено поле с типом %s", f.getType()));
    }

    /**
     * Поиск по аннотации "Name"
     */
    private void checkNamedAnnotations() {
        List<String> list = Arrays.stream(getClass().getDeclaredFields())
                .filter(f -> f.getDeclaredAnnotation(Name.class) != null)
                .map(f -> f.getDeclaredAnnotation(Name.class).value())
                .collect(toList());
        if (list.size() != new HashSet<>(list).size()) {
            throw new IllegalStateException("Найдено несколько аннотаций @Name с одинаковым значением в классе " + this.getClass().getName());
        }
    }

    /**
     * Поиск и инициализация элементов страницы без аннотации Optional или Hidden
     */
    private List<SelenideElement> readWithWrappedElements() {
        return Arrays.stream(getClass().getDeclaredFields())
                .filter(f -> f.getDeclaredAnnotation(Optional.class) == null && f.getDeclaredAnnotation(Hidden.class) == null)
                .map(this::extractFieldValueViaReflection)
                .flatMap(v -> v instanceof List ? ((List<?>) v).stream() : Stream.of(v))
                .map(AkitaPage::castToSelenideElement)
                .filter(Objects::nonNull)
                .collect(toList());
    }

    /**
     * Поиск и инициализация элементов страницы c аннотацией Hidden
     */
    private List<SelenideElement> readWithHiddenElements() {
        return Arrays.stream(getClass().getDeclaredFields())
                .filter(f -> f.getDeclaredAnnotation(Hidden.class) != null)
                .map(this::extractFieldValueViaReflection)
                .flatMap(v -> v instanceof List ? ((List<?>) v).stream() : Stream.of(v))
                .map(AkitaPage::castToSelenideElement)
                .filter(Objects::nonNull)
                .collect(toList());
    }

    /**
     * Поиск элементов страницы с типом ElementsCollection, не помеченных как Optional или Hidden
     * @return
     */
    private List<ElementsCollection> readPrimaryElementsCollections() {
        return Arrays.stream(getClass().getDeclaredFields())
                .filter(f -> f.getType().equals(ElementsCollection.class))
                .filter(f -> f.getDeclaredAnnotation(Optional.class) == null && f.getDeclaredAnnotation(Hidden.class) == null)
                .map(f -> (ElementsCollection) Reflection.extractFieldValue(f, this))
                .collect(toList());
    }

    private Object extractFieldValueViaReflection(Field field) {
        return Reflection.extractFieldValue(field, this);
    }
}
